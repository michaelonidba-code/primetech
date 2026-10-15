# Ads Eventing Platform

A real-time advertising event pipeline modelled on the systems behind Connected TV ad tiers:
ingesting billions of playback events, processing them in a stream, pacing budgets and
reporting with low latency. It is built to run on one machine with Docker Compose, and to be
measured.

This repository is being built in phases. **Phase 1 (the event backbone) is complete.**

```
 Player SDKs ──► Ingest Gateway ──► Kafka (Redpanda) ──► [Phase 2] Flink ──► [Phase 2] Druid ──► Dashboard
 (simulator)     validate · scrub      ad-events.v1         dedup · enrich        real-time OLAP     (Nuxt)
                 · ack after durable   ad-events-dlq.v1     · window · IVT
                        │
                        └──► /stats, /metrics ─────────────────────────────────────────────────► Dashboard
```

| Phase | Scope | Status |
|---|---|---|
| 1. Event backbone | Event schema, ingest gateway, Kafka topics, traffic simulator, live dashboard | ✅ Done |
| 2. Stream processing | Flink: dedup by event id, late-event handling, enrichment, windowed aggregation → Druid | Next |
| 3. Ad serving | Ad decisioning, OpenRTB 2.6 auctions against mock DSPs, frequency caps | Planned |
| 4. Pacing & budgets | Redis spend counters, PID pacing loop fed by Flink | Planned |
| 5. Audit & observability | Reconciliation against simulator ground truth, Prometheus/Grafana, alerts | Planned |
| 6. Load testing & write-up | Benchmarks, failure drills, design docs | Planned |

## Quick start

Requires Docker with Compose v2.

```bash
docker compose up --build
```

| What | Where |
|---|---|
| Dashboard | http://localhost:3000 |
| Gateway API | http://localhost:8080 (`/v1/events`, `/stats`, `/metrics`, `/health`) |
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
| `event-simulator/` | Realistic, deliberately messy player traffic with a ground-truth tally |
| `dashboard/` | Nuxt 4 SPA polling the gateway: throughput, playback funnel, rejections |
| `infra/` | Topic creation script |

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

| Topic | Partitions | Retention | Key |
|---|---|---|---|
| `ad-events.v1` | 12 | 7 days | `sessionId` |
| `ad-events-dlq.v1` | 3 | 14 days | `eventId` |

## Measured results

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
| `CAMPAIGNS`, `SEED` | simulator | `50`, `42` |
| `NUXT_PUBLIC_GATEWAY_URL` | dashboard | `http://localhost:8080` (or `?gateway=` in the URL) |

## CI / deployment

* `.github/workflows/ci.yml` runs the Maven build and tests, the dashboard typecheck and static
  build, and builds every Docker image.
* `.github/workflows/nuxtjs.yml` publishes the dashboard to GitHub Pages (enable Pages with source
  "GitHub Actions"). The hosted page talks to whichever gateway you give it with `?gateway=`.

## Known issues

* `npm audit` reports two high-severity advisories in the dashboard's build tooling (`braces`
  via nitropack's globby, `node-forge` via the dev server's listhen). Neither ships in the
  built dashboard, and no fixed versions exist yet.
* Installing new dashboard dependencies with npm 10 hits an npm resolver bug with Nuxt ≥ 4.4.
  `npm ci` from the lockfile works. To change dependencies, use `npx npm@11 install ...`.
