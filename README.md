# Ads Eventing Platform

A real-time advertising event pipeline modelled on the systems behind Connected TV ad tiers:
ingesting billions of playback events, processing them in a stream, pacing budgets and
reporting with low latency. It is built to run on one machine with Docker Compose, and to be
measured.

This repository is being built in phases. **Phases 1 (event backbone), 2 (stream processing
and real-time OLAP) and 3 (ad serving with OpenRTB auctions) are complete.**

```
                 ┌────────────── OpenRTB 2.6 ──────────────┐
                 ▼                                          │
            3 mock DSPs                                     │
                 │ bids                                     │
 Player ─ad request─► Ad Server ── decision log ──► Kafka: ad-decisions.v1
 (simulator) ◄─pod──  auction, caps (Redis), floors          campaign-metadata.v1 ──┐
    │                                                                               │
    └─tracking events─► Ingest Gateway ─► Kafka ─► Flink ─► Kafka ────────────────► Druid ─► Dashboard
                        validate, scrub   ad-      dedup, enrich, IVT,   ad-events-clean.v1   rollup   (Nuxt)
                        ack when durable  events   1-min windows         line-item-minutes.v1 per min
                                          .v1      (exactly-once)
```

| Phase | Scope | Status |
|---|---|---|
| 1. Event backbone | Event schema, ingest gateway, Kafka topics, traffic simulator, live dashboard | ✅ Done |
| 2. Stream processing | Flink: exactly-once dedup, enrichment, invalid-traffic detection, windowed aggregation with late data; Druid ingestion; reporting panels | ✅ Done |
| 3. Ad serving | Ad server with direct demand and OpenRTB 2.6 auctions across three mock DSPs, frequency caps, decision log; simulator plays what it serves | ✅ Done |
| 4. Pacing & budgets | Redis spend counters, PID pacing loop fed by Flink, guaranteed line items | Next |
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
| Ad server API | http://localhost:8090 (`/v1/ad-requests`, `/stats`, `/metrics`, `/health`) |
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
| `ads-common/` | Event model (`ClientEvent` in, `AdEvent` out), validation rules, device-id pseudonymisation, campaign catalog, ad request/response and decision records |
| `ingest-gateway/` | HTTP edge service: validates, scrubs, writes to Kafka, exposes metrics |
| `event-simulator/` | Realistic, deliberately messy player traffic (duplicates, late events, bots) with a ground-truth tally; publishes the campaign catalog |
| `stream-processor/` | Flink 2.2 job: dedup, enrichment, IVT, minute totals, exactly-once Kafka output |
| `openrtb/` | OpenRTB 2.6 bid request/response model (the CTV ad pod subset) |
| `ad-server/` | Ad decisioning: direct line items + OpenRTB auction, frequency caps, decision log |
| `mock-dsp/` | Configurable OpenRTB bidder: bid rate, value and latency distributions, bid shading |
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
| `campaign-metadata.v1` | 1 | compacted | `campaignId` | ad server (direct catalog at start-up, DSP campaigns on first win) |
| `ad-events-clean.v1` | 12 | 7 days | `sessionId` | Flink, transactional |
| `ad-decisions.v1` | 12 | 7 days | `requestId` | ad server |
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

## Phase 3 design: ad serving

### Request flow

1. A player starting an ad break sends `POST /v1/ad-requests` (session, device, consent,
   country, number of slots, break length).
2. The ad server pseudonymises the device id with the **same keyed hash as the gateway**, so
   frequency caps, reporting and IVT detection all see the same ids.
3. It sends **one OpenRTB 2.6 bid request to every DSP in parallel**: one `imp` per slot, with the
   2.6 pod fields (`podid`, `slotinpod`, `poddur`), the floor, `tmax` (120 ms), the device as
   `devicetype`, alpha-3 `geo.country` and `ifa` (hashed, and only with consent, otherwise `lmt=1`).
4. While the DSPs think, it filters direct-sold line items by targeting (country, device type).
5. One Redis `MGET` drops every candidate whose campaign has hit its hourly frequency cap for this
   device.
6. **The pod auction** fills slots in order. Each slot is a unified auction between direct line
   items and that slot's bids:
   * bids below the floor are ignored;
   * **competitive separation:** an advertiser appears at most once per break;
   * ads must fit the remaining break;
   * **first price** by default (DSPs shade their bids); **second price** is available
     (runner-up + 1¢, a sole bidder pays the floor). Direct line items pay their contracted CPM.
7. Winners: caps are incremented (pipelined `INCR` + `EXPIRE`), win notices (`nurl` with
   `${AUCTION_PRICE}`) go to winning DSPs, and the full decision (every DSP outcome, every slot's
   bid and clearing price) goes to `ad-decisions.v1`.
8. The player plays the pod and reports the `auctionId` and clearing price on its tracking events,
   so every billed impression can be joined back to the auction that sold it.

### Mock DSPs

Three bidders with deliberately different trade-offs, drawn from log-normal value and latency
distributions:

| DSP | Bid rate | Median value (CPM) | Median latency | Character |
|---|---|---|---|---|
| `premium-dsp` | 55% | $34 | 35 ms | Selective, high value, fast |
| `budget-dsp` | 90% | $17 | 22 ms | Bids on nearly everything, cheap |
| `slow-dsp` | 75% | $40 | 85 ms (fat tail) | Values impressions highly, but often misses `tmax` |

They shade bids to 85–90% of value in first-price auctions and bid true value in second-price
ones, respect floors and slot durations, and count win notices.

### Decisions

* **A slow or broken DSP costs only its own bids.** Every DSP call becomes a result (BID,
  NO_BID, TIMEOUT, ERROR); the auction waits at most `tmax` and proceeds with whatever arrived.
* **The auction deadline is not the HTTP timeout.** The auction stops waiting at `tmax`, but the
  HTTP exchange may run on (up to 1 s) so the connection goes back to the pool. Aborting at `tmax`
  closed connections, and under load the reconnects cost more CPU than anything else in the
  server (see below).
* **Admission control.** At most 256 decisions run at once; beyond that the server answers `503`
  immediately. An unfilled break is a normal outcome for a player; a hung ad server is not.
* **Fail open on Redis.** A cap check gets a 20 ms pool wait and a 50 ms socket timeout. If Redis
  is slow, the ad is served uncapped and the error counted.
* **Caps count serves, not impressions.** Incrementing at decision time is immediate and cheap;
  the cost is counting the occasional served-but-unplayed ad.
* **Interim throttle on direct demand.** Without budgets, ~100 direct line items at up to $60 CPM
  would win almost every slot. Until Phase 4 adds pacing, each line item enters an auction with
  probability `DIRECT_PARTICIPATION` (1.5%). This is a stand-in, documented as such.

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

### Phase 3: ad serving

**Correctness.** Full stack (ad server, 3 DSPs, Redis, gateway, Flink, Druid) on the same 4 vCPU VM,
2,000 events/s (about 230 auctions/s) for 150 s, every output read back:

| Check | Result |
|---|---|
| Clean events vs simulator ground truth | **187,027 = 187,027**, 0 duplicates, 0 missing metadata |
| Played impressions joined to their auction (campaign won a slot, price = clearing price) | **44,610 / 44,610** |
| Druid billable spend by source vs the auction join | **identical**: $922.73 direct, $750.67 programmatic |
| Frequency cap counters in Redis above the highest cap (4) | **0** |
| Fill rate | 85% |
| Played / served ads | 81% (viewers abandon breaks part-way) |

**Latency and capacity.** Ad server, DSPs, Redis, Redpanda, gateway and the load generator on the
same 4 vCPUs (Flink and Druid stopped), 60 s per step:

| Offered load | Auctions/s | p50 / p99 | Fast-DSP timeouts | Fill |
|---|---|---|---|---|
| 2,000 events/s | 227 | **91 / 129 ms** | 6% | 95% |
| 5,000 events/s | 542 | 104 / 318 ms | 20% | 88% |
| 10,000 events/s | 808 | 149 / 333 ms | 50–57% | 72% |

p50 sits near `tmax` because each auction waits for the slow DSP. Beyond ~250 auctions/s the box
runs out of CPU: the load generator, three DSP JVMs and the ad server share four cores, so even
the fast DSPs miss the deadline. It degrades gradually rather than failing.

**What testing found and fixed:**

* **The ad server hung under overload.** It accepted unbounded work; requests outlived the
  clients' 2 s timeout, and 13,062 abandoned connections sat in `CLOSE_WAIT` until it ran out of
  file descriptors (20,000/20,000) and stopped answering even `/health`. Fixed with admission
  control (bounded in-flight decisions, immediate 503) and a fail-fast Redis pool; covered by
  `AdServerHttpTest.shedsLoadInsteadOfQueueing`.
* **Connection churn was the biggest CPU cost.** A JFR profile showed 26% of CPU in the JDK HTTP
  client, mostly its connection pool: every `tmax` timeout closed a connection and the next call
  reconnected. Letting the exchange finish in the background (and serializing each bid request
  once instead of per DSP) took the 10k-events/s step from 478 auctions/s at p99 2,143 ms to 808
  auctions/s at p99 333 ms, and the 2k step from p50 121 ms to 91 ms.
* **The campaign catalog was silently never published.** With `max.block.ms=0`, every send failed
  until the producer had fetched topic metadata, so the start-up announcement of all 50 direct
  campaigns was dropped, and the campaigns were marked announced anyway. 82k events then lacked
  metadata. Now the producer fetches metadata before serving, a campaign counts as announced only
  once the write is acknowledged, and the ad server does not start serving until the catalog is in Kafka.
* **Second-price auctions overcharged sole bidders by 1¢** (floor + 1¢ instead of the floor).
  Caught by `AuctionTest`.

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
| `AD_SERVER_URL`, `AD_REQUEST_CONCURRENCY` | simulator | unset (local catalog), `256` |
| `KAFKA_BOOTSTRAP` | simulator | unset; without an ad server, publishes the campaign catalog |
| `DSP_ENDPOINTS` | ad server | `name=url,...` |
| `TMAX_MS`, `FLOOR_CPM`, `AUCTION_TYPE` | ad server | `120`, `5.00`, `first` (or `second`) |
| `DIRECT_PARTICIPATION`, `MAX_IN_FLIGHT`, `DSP_FREQUENCY_CAP_PER_HOUR` | ad server | `0.015`, `256`, `4` |
| `REDIS_URL`, `DEVICE_ID_SECRET` | ad server | unset = in-process caps; secret must match the gateway's |
| `DSP_PROFILE`, `PUBLIC_URL` | mock DSP | `premium`, `budget` or `slow`; URL for win notices |
| `DEDUP_TTL_MINUTES` | stream-processor | `30` |
| `OUT_OF_ORDERNESS_SECONDS`, `ALLOWED_LATENESS_MINUTES`, `LATE_FIRING_DELAY_SECONDS` | stream-processor | `5`, `10`, `5` |
| `IVT_MAX_IMPRESSIONS_PER_DEVICE_MINUTE` | stream-processor | `20` |
| `METADATA_WAIT_SECONDS`, `CHECKPOINT_INTERVAL_SECONDS` | stream-processor | `60`, `10` |
| `NUXT_PUBLIC_GATEWAY_URL` | dashboard | `http://localhost:8080` (or `?gateway=` in the URL) |
| `NUXT_FLINK_URL`, `NUXT_DRUID_URL`, `NUXT_AD_SERVER_URL` | dashboard (server) | `http://localhost:8082`, `http://localhost:8888`, `http://localhost:8090` |

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
* The ad server has a cold-start transient: in the first ~20 s after start, before the JIT and
  DSP connection pools warm up, some auctions time out or are shed (1,432 failed requests at the
  start of the measured run, then none).
* Direct demand is throttled by a fixed participation probability until pacing replaces it in
  Phase 4.
* Druid's supervisor reports a lag of a few records per partition on the clean topic. Those are
  Kafka transaction markers, which a `read_committed` consumer never receives as records.
* Flink operator metrics (duplicates dropped, IVT flagged) restart from zero when the job
  restores; they are rates and health signals, not the source of truth. Counts come from the topics.
* Installing new dashboard dependencies with npm 10 hits an npm resolver bug with Nuxt ≥ 4.4.
  `npm ci` from the lockfile works. To change dependencies, use `npx npm@11 install ...`.
