# Ads Eventing Platform

A real-time advertising event pipeline modelled on the systems behind Connected TV ad tiers:
ingesting billions of playback events, processing them in a stream, pacing budgets and
reporting with low latency. It is built to run on one machine with Docker Compose, and to be
measured.

This repository is being built in phases. **Phases 1 (event backbone) and 2 (stream
processing and real-time OLAP) are complete.**

```
 Player SDKs ─► Ingest Gateway ─► Kafka ─────────► Flink ───────────────────► Kafka ──────────────► Druid ─► Dashboard
 (simulator)    validate, scrub   ad-events.v1     dedup by event id          ad-events-clean.v1    rollup   (Nuxt)
                ack when durable  ad-events-dlq.v1 enrich (broadcast)         line-item-minutes.v1  per min
                                  campaign-        flag invalid traffic       (exactly-once,
                                  metadata.v1 ───► 1-min windows + lateness    transactional)
```

| Phase | Scope | Status |
|---|---|---|
| 1. Event backbone | Event schema, ingest gateway, Kafka topics, traffic simulator, live dashboard | ✅ Done |
| 2. Stream processing | Flink: exactly-once dedup, enrichment, invalid-traffic detection, windowed aggregation with late data; Druid ingestion; reporting panels | ✅ Done |
| 3. Ad serving | Ad decisioning, OpenRTB 2.6 auctions against mock DSPs, frequency caps | Next |
| 4. Pacing & budgets | Redis spend counters, PID pacing loop fed by Flink | Planned |
| 5. Audit & observability | Reconciliation against simulator ground truth, Prometheus/Grafana, alerts | Planned |
| 6. Load testing & write-up | Benchmarks, failure drills, design docs | Planned |

## Quick start

Requires Docker with Compose v2 and about 10 GB of memory for Docker: the full stack is
Redpanda, Flink and a seven-container Druid cluster.

```bash
docker compose up --build
```

| What | Where |
|---|---|
| Dashboard | http://localhost:3000 |
| Gateway API | http://localhost:8080 (`/v1/events`, `/stats`, `/metrics`, `/health`) |
| Flink web UI | http://localhost:8082 |
| Druid console and SQL | http://localhost:8888 |
| Redpanda Console (browse topics) | http://localhost:8081 |
| Kafka API for host tools | `localhost:19092` |

The simulator starts sending ~2,000 events/s straight away. Change the load with environment variables:

```bash
TARGET_EPS=50000 CONCURRENCY=32 docker compose up -d simulator
```

### Without Docker

Java 21 and Maven, Node 22:

```bash
mvn verify                                   # build + all tests
docker compose up -d redpanda topics         # just the broker
DEVICE_ID_SECRET=local-dev-secret-change-me java -jar ingest-gateway/target/ingest-gateway.jar
java -jar event-simulator/target/event-simulator.jar
cd dashboard && npm ci && npm run dev
```

## Modules

| Path | What it is |
|---|---|
| `ads-common/` | Event model (`ClientEvent` in, `AdEvent` out), validation rules, device-id pseudonymisation |
| `ingest-gateway/` | HTTP edge service: validates, scrubs, writes to Kafka, exposes metrics |
| `event-simulator/` | Realistic, deliberately messy player traffic (duplicates, late events, bots) with a ground-truth tally; publishes the campaign catalog |
| `stream-processor/` | Flink 2.2 job: dedup, enrichment, IVT, minute totals, exactly-once Kafka output |
| `dashboard/` | Nuxt 4 app: gateway throughput, Flink health and Druid campaign delivery |
| `infra/` | Topic creation script; Druid configuration and ingestion spec |

## Phase 1 design

### Event model

Players fire VAST-style tracking events: `IMPRESSION`, `FIRST_QUARTILE`, `MIDPOINT`,
`THIRD_QUARTILE`, `COMPLETE`, `CLICK`. Each carries a client-generated UUID `eventId`, which is
the idempotency key the stream processor will de-duplicate on.

The gateway turns a `ClientEvent` (wire format, may hold a raw device id) into an `AdEvent`
(canonical, `schemaVersion: 1`) before anything touches Kafka.

### Ingest gateway

* **At-least-once, acknowledged only when durable.** A batch gets `200` only after Kafka has
  acked every event with `acks=all`. If any write fails the whole batch gets `503 Retry-After`,
  and the client resends it. Duplicates caused by that retry are expected, and Phase 2 removes them
  by `eventId`. That is cheaper and simpler than exactly-once at the edge.
* **Per-event validation, never per-batch failure.** One bad event doesn't sink 199 good ones.
  Rejects go to `ad-events-dlq.v1` with a reason, and the response lists them by index so the
  client doesn't retry them.
* **Keyed by `sessionId`.** All events of one playback land on one partition in order, which the
  Phase 2 funnel and invalid-traffic logic rely on.
* **Producer tuned for throughput:** idempotent producer, lz4, 5 ms linger, 128 KB batches.
* **Virtual threads.** Each request blocks waiting for its Kafka acks. On virtual threads that is
  cheap, so the code stays straightforward instead of callback-driven.
* **Late events are accepted** (up to 24 h old). Dropping them would under-count billing. The
  stream processor decides which window they land in. Events more than 5 min in the future are
  rejected as clock skew.

Validation rejects: missing fields, unknown event types, ids over 64 chars, non ISO-3166 country
codes, timestamps out of bounds, a price on a non-billable event, or a missing/negative price on an
impression.

### Privacy

* Raw device ids **never reach Kafka**, including the DLQ.
* With consent, the id is pseudonymised with **HMAC-SHA256** under a server-side secret
  (`DEVICE_ID_SECRET`). A plain hash could be reversed by hashing every known id; a keyed one
  cannot, and rotating the secret unlinks old data from new.
* Without consent the id is dropped entirely, not hashed.

### Simulator

Produces ad breaks the way real viewers watch them: a pod of 1–3 ads, quartiles fired in order
with a 3% drop-off at each step (a viewer who abandons an ad leaves the break), ~0.4% click-through,
and a power-law spread across 50 campaigns. Sessions are planned up front but emitted lazily,
so ~225k concurrent sessions fit in a small heap.

It injects the faults the pipeline has to survive, and counts them so later phases can be
checked against exact numbers:

| Fault | Default rate | Why |
|---|---|---|
| Duplicate (same `eventId` resent 1–10 s later) | 1% | Lost responses and retries |
| Late (held back 30 s, original timestamp) | 2% | Devices buffering offline |
| Invalid (bad country, future time, bad price, missing field) | 0.5% | Buggy or spoofed clients |

### Topics

| Topic | Partitions | Retention | Key | Written by |
|---|---|---|---|---|
| `ad-events.v1` | 12 | 7 days | `sessionId` | gateway |
| `ad-events-dlq.v1` | 3 | 14 days | `eventId` | gateway |
| `campaign-metadata.v1` | 1 | compacted | `campaignId` | simulator (stand-in for campaign management) |
| `ad-events-clean.v1` | 12 | 7 days | `sessionId` | Flink, transactional |
| `line-item-minutes.v1` | 6 | compacted + 7 days | `lineItemId\|windowStart` | Flink, transactional |

## Phase 2 design: stream processing

### Topology

```
ad-events.v1 ─► keyBy eventId ─► Deduplicate ─► keyBy campaignId ─► EnrichWithCampaign ◄── broadcast(campaign-metadata.v1)
                                                                          │
                       ┌────────────── has device id ──────────────────────┤
                       ▼                                                   │ no device id (no consent)
          keyBy deviceIdHash ─► InvalidTrafficFilter ──────► union ◄───────┘
                                                               │
                                       RestoreEventTime ─► ad-events-clean.v1   (transactional sink)
                                                               │
                                     keyBy lineItemId ─► 1-min event-time windows ─► line-item-minutes.v1
```

The topology lives in `AdPipeline`, independent of Kafka, so tests drive it with in-memory
sources on a local Flink cluster. `PipelineJob` wires it to Kafka.

### Decisions

* **Exactly-once end to end.** RocksDB state is checkpointed every 10 s, and both outputs are
  written inside Kafka transactions committed on each checkpoint. Readers use
  `read_committed`, Druid included. The price is latency: output becomes visible up to one
  checkpoint interval after it is produced.
* **De-duplication by event id** with state TTL (30 min). The gateway is at-least-once by design,
  so this is where retries are removed. State is one small entry per event in the window,
  kept in RocksDB rather than on the heap.
* **Enrichment by broadcast state.** Campaign metadata is small and read by every event, so it is
  broadcast to every instance instead of looked up remotely. Events for a campaign whose
  metadata hasn't arrived yet are buffered per campaign and released when it does (after 60 s
  they are released unenriched and counted). Without this, events processed just after start-up
  would race the metadata.
* **Invalid traffic is flagged, not dropped.** A device firing more than 20 impressions in one
  event-time minute is a bot; its later events in that minute get `ivtReason` and are excluded
  from spend. Keeping them lets advertisers see what was filtered. Devices without consent have
  no id, so this rule can't see them; that is the price of privacy-first tracking.
* **Minute totals with late data.** Windows fire when the watermark (5 s out-of-orderness) passes,
  then again for late events up to 10 minutes, as an upsert stream with a `revision`. A custom
  `CoalescingLateTrigger` batches late re-firings to at most one per 5 s per window: firing per
  late event produced up to 217 revisions of a single minute in testing; batching brought it to 6.
* **Event time is restored before windowing.** Records released from broadcast state or a
  processing-time timer lose their timestamp; `RestoreEventTime` sets it from the payload so
  they land in the right window.
* **Restart safety without HA.** Sources resume from committed offsets (committed on each
  checkpoint) rather than `earliest`, so a job manager restart without a checkpoint doesn't
  replay the topic against empty dedup state.

### Druid

`ad-events-clean.v1` is ingested by Druid's Kafka indexing service (`infra/druid/ad-events-supervisor.json`)
with roll-up to one row per minute and dimension combination. Billing excludes IVT
(`ivt = 0`); unique devices use an HLL sketch. The dashboard queries it through a server route,
for example:

```sql
SELECT advertiserName,
       SUM(events)      FILTER (WHERE type = 'IMPRESSION' AND ivt = 0) AS impressions,
       SUM(priceMicros) FILTER (WHERE type = 'IMPRESSION' AND ivt = 0) AS spend_micros
FROM ad_events
WHERE __time >= CURRENT_TIMESTAMP - INTERVAL '1' HOUR
GROUP BY 1 ORDER BY 3 DESC
```

## Measured results

### Phase 1: ingest

Measured on a 4 vCPU, 16 GB cloud VM, with the whole stack (Redpanda, gateway, simulator,
dashboard) sharing that machine. Target 50,000 events/s, 200 events per batch, 32 concurrent
client connections, 1 GB gateway heap:

| Metric | Result |
|---|---|
| Sustained throughput | **~51,500 events/s** (≈ 4.4 billion/day) |
| Batch latency (POST including Kafka `acks=all`), steady state | **p50 ≈ 9 ms, p99 14–23 ms** |
| Gateway memory | ~1.1 GB |
| Failed batches / retries | 0 / 0 |
| Invalid events injected vs rejected vs in DLQ | 39,464 / 39,464 / 39,464 |
| Raw device ids found in 3,000 sampled Kafka records | 0 |

Two things that showed up while measuring:

* **Cap the JVM heaps.** With no `-Xmx`, ZGC grew the gateway to 7.4 GB and the simulator to
  3.8 GB, competing with Redpanda for memory. p99 was 55–80 ms. With fixed heaps (1 GB and 2 GB,
  set in `docker-compose.yml`) it dropped to 14–23 ms at the same throughput.
* p99 is higher in the first minute, while the JIT warms up and sessions ramp.

### Phase 2: correctness end to end

Same VM, simulator at 5,000 events/s for 150 s with its default faults (1% duplicates, 2% late by
30 s, 0.5% invalid, 1% bot sessions from 5 devices). Each output was read back with
`read_committed` and compared with the simulator's ground truth:

| Check | Expected | Result |
|---|---|---|
| Events in `ad-events-clean.v1` | 636,872 unique valid sent | **636,872**, identical per event type |
| Duplicates in output (6,397 injected) | 0 | **0** |
| Events missing campaign metadata | 0 | **0** |
| Line-item minutes matching a recount of the clean events | all closed windows | **306 / 306** |
| Invalid traffic flagged | bot devices only | 4,782 of 5,559 bot events (the first 20 per device-minute pass) |

**Failure drill.** During a second run the Flink TaskManager was killed (`docker kill`) and
restarted. The job restored from its last checkpoint, and afterwards:

| Check | Result |
|---|---|
| Clean events vs unique event ids in the input topic | **956,446 = 956,446**, 0 missing, 0 duplicates |
| Line-item minutes matching a recount | **612 / 612** |
| Druid vs the clean topic (events, per type, IVT, billable spend) | **identical**: 956,446 events, 8,537,992,000 µ$ billable |

Druid rolled the 956k events up into 228k rows (4.2×).

## API

`POST /v1/events`

```json
{"events": [{
  "eventId": "6f1c...", "type": "IMPRESSION", "eventTimeMs": 1760000000000,
  "sessionId": "s-1", "adId": "ad-1", "creativeId": "cr-1", "campaignId": "camp-1", "lineItemId": "li-1",
  "deviceId": "raw-device-id", "deviceType": "CTV", "country": "US", "consent": true,
  "priceMicros": 25000
}]}
```

| Status | Meaning |
|---|---|
| `200` | `{"accepted": n, "rejected": m, "errors": [{"index", "eventId", "reason"}]}`. Rejected events are final |
| `400` | Malformed JSON or empty batch |
| `413` | More than 1,000 events or larger than 1 MiB |
| `503` | Kafka didn't confirm every event. Retry the whole batch (`Retry-After: 1`) |

`GET /stats` returns JSON counters for the dashboard. `GET /metrics` serves Prometheus metrics,
including Kafka producer and JVM metrics.

## Configuration

| Variable | Service | Default |
|---|---|---|
| `DEVICE_ID_SECRET` | gateway | **required**, ≥ 16 chars (Compose sets a dev value) |
| `KAFKA_BOOTSTRAP` | gateway | `localhost:19092` |
| `MAX_EVENTS_PER_BATCH`, `MAX_BODY_BYTES` | gateway | `1000`, `1048576` |
| `TARGET_EPS`, `BATCH_SIZE`, `CONCURRENCY` | simulator | `2000`, `200`, `16` |
| `DURATION_SECONDS` | simulator | `0` (run forever) |
| `DUPLICATE_RATE`, `LATE_RATE`, `INVALID_RATE`, `LATE_DELAY_MS` | simulator | `0.01`, `0.02`, `0.005`, `30000` |
| `BOT_SESSION_SHARE`, `BOT_DEVICES` | simulator | `0.01`, `5` |
| `CAMPAIGNS`, `SEED` | simulator | `50`, `42` (event ids derive from the seed) |
| `KAFKA_BOOTSTRAP` | simulator | unset; when set, publishes the campaign catalog |
| `DEDUP_TTL_MINUTES` | stream-processor | `30` |
| `OUT_OF_ORDERNESS_SECONDS`, `ALLOWED_LATENESS_MINUTES`, `LATE_FIRING_DELAY_SECONDS` | stream-processor | `5`, `10`, `5` |
| `IVT_MAX_IMPRESSIONS_PER_DEVICE_MINUTE` | stream-processor | `20` |
| `METADATA_WAIT_SECONDS`, `CHECKPOINT_INTERVAL_SECONDS` | stream-processor | `60`, `10` |
| `NUXT_PUBLIC_GATEWAY_URL` | dashboard | `http://localhost:8080` (or `?gateway=` in the URL) |
| `NUXT_FLINK_URL`, `NUXT_DRUID_URL` | dashboard (server) | `http://localhost:8082`, `http://localhost:8888` |

## CI / deployment

* `.github/workflows/ci.yml` runs the Maven build and tests, the dashboard typecheck and static
  build, and builds every Docker image.
* `.github/workflows/nuxtjs.yml` publishes the dashboard to GitHub Pages (enable Pages with source
  "GitHub Actions"). The hosted page talks to whichever gateway you give it with `?gateway=`;
  the Flink and Druid panels need the server routes, so they only work in the Docker stack.

## Known issues

* `npm audit` reports two high-severity advisories in the dashboard's build tooling (`braces`
  via nitropack's globby, `node-forge` via the dev server's listhen). Neither ships in the
  built dashboard, and no fixed versions exist yet.
* Druid's supervisor reports a lag of a few records per partition on the clean topic. Those are
  Kafka transaction markers, which a `read_committed` consumer never receives as records.
* Flink operator metrics (duplicates dropped, IVT flagged) restart from zero when the job
  restores; they are rates and health signals, not the source of truth. Counts come from the topics.
* Installing new dashboard dependencies with npm 10 hits an npm resolver bug with Nuxt ≥ 4.4.
  `npm ci` from the lockfile works. To change dependencies, use `npx npm@11 install ...`.
