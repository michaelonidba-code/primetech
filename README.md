# Ads Eventing Platform

A real-time advertising event pipeline modelled on the systems behind Connected TV ad tiers:
ingesting billions of playback events, processing them in a stream, pacing budgets and
reporting with low latency. It is built to run on one machine with Docker Compose, and to be
measured.

This repository is being built in phases. **Phases 1 (event backbone), 2 (stream processing
and real-time OLAP), 3 (ad serving with OpenRTB auctions), 4 (budget pacing), 5 (audit and
observability) and 6 (load testing and write-up) are complete.** Start with the design document,
[docs/DESIGN.md](docs/DESIGN.md): decisions, correctness, failure behaviour and a capacity model.
The dashboard installs as an app on desktop and phone and can be hosted behind HTTPS with
sign-in ([Hosting](#hosting-the-dashboard-as-an-app)).

Also in `docs/`: the full technical report as a PDF with every source file
([docs/report](docs/report/Quila-Visuals-Ads-Eventing-Report.pdf), rebuilt with
`python3 docs/report/build_report.py . docs/report/Quila-Visuals-Ads-Eventing-Report.pdf`), a one-page
illustrated overview ([docs/overview.html](docs/overview.html)), app screenshots
([docs/screenshots](docs/screenshots)) and the Quila Visuals logo files ([docs/brand](docs/brand)).

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
| 4. Pacing & budgets | Line item budgets and tiers, pacing service (closed-loop controller fed by Flink), served-spend counters, guaranteed priority | ✅ Done |
| 5. Audit & observability | Continuous reconciliation service, Prometheus, Grafana, alert rules, Alertmanager, push notifications to the installed app, failure drills | ✅ Done |
| 6. Load testing & write-up | Load test harness and results, profiling, capacity model, design document | ✅ Done |

## Quick start

Requires Docker with Compose v2 and about 10 GB of memory for Docker: the full stack is
Redpanda, Flink and a seven-container Druid cluster.

```bash
docker compose up --build
```

| What | Where |
|---|---|
| Dashboard | http://localhost:3000 (sign-in is off locally; see [Hosting](#hosting-the-dashboard-as-an-app)) |
| Gateway API | http://localhost:8080 (`/v1/events`, `/stats`, `/metrics`, `/health`) |
| Ad server API | http://localhost:8090 (`/v1/ad-requests`, `/stats`, `/metrics`, `/health`) |
| Pacing service | http://localhost:8095 (`/stats`, `/metrics`, `/health`) |
| Audit service | http://localhost:8096 (`/stats`, `/metrics`, `/health`) |
| Grafana (dashboard "Ads Eventing: operations") | http://localhost:3001 |
| Prometheus, Alertmanager | http://localhost:9090, http://localhost:9093 |
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
| `pacing-service/` | Budget pacing: a controller per line item, fed by Flink's delivered spend, steering the ad server through Redis |
| `audit-service/` | Continuous reconciliation of independent records of the same money (Flink vs Druid, Redis vs the decision log, budgets, delivery) |
| `loadtest/` | Load test harness (steps the simulator, reads Prometheus) and report generator |
| `infra/prometheus`, `infra/alertmanager`, `infra/grafana` | Scrape config, alert rules and their unit tests, routing and inhibition, provisioned dashboard |
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
| `line-items.v1` | 1 | compacted | `lineItemId` | ad server (tiers and budgets, read by the pacing service) |
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
* **Direct demand is paced.** Without budgets, ~100 direct line items at up to $60 CPM would win
  almost every slot. Phase 3 shipped a fixed participation probability as a stand-in; Phase 4
  replaces it with per-line-item pacing (below), keeping the fixed value only as the fallback.

## Phase 4 design: budgets and pacing

### The loop

```
ad server ──served spend (HSET, every 250 ms)──► Redis ◄──throttle, stop threshold── pacing service
    ▲                                            │                                       ▲
    └──── reads pacing state every second ───────┘                                       │
                                                                                         │
player ─► gateway ─► Flink ─► line-item-minutes.v1 (delivered spend, exactly-once) ──────┘
```

* Every direct line item has a **tier** and a **budget per pacing period**. Guaranteed line items
  (sponsorships) take a slot ahead of the auction whenever pacing lets them compete; standard ones
  compete on price with programmatic demand. The period is a day in real deployments and
  configurable here (`PACING_PERIOD_MINUTES`, 30 by default) so a whole cycle fits in a demo.
* The **pacing service** keeps one controller per line item. Every 10 seconds it sets a
  **throttle**, the probability the line item enters an auction, and a **stop threshold**.
* The **ad server** reads both from Redis once a second (never per request), applies the
  throttle when building candidates, and counts what it serves.

### Two sources of truth, two speeds

| | Served spend (ad server, Redis) | Delivered spend (Flink minute totals) |
|---|---|---|
| Latency | immediate for an instance's own spend, ≤ 1 s for others' | ~70 s (minute window, watermark, checkpoint) |
| Accuracy | overstates: some served ads are never played (1–4% of spend in the live run) | exact: de-duplicated, IVT excluded, exactly-once |
| Used for | the control loop's feedback, stopping on time | budget accounting, what remaining budget means |

The pacer combines them: remaining budget comes from delivered spend, and is converted into
served spend using the observed delivered/served ratio. The ad server stops a line item the
moment its live served counter reaches that threshold, without waiting a minute for Flink.

### Control law

Rather than a textbook PID on spend, each pacer learns a **yield**, the delivered spend per
minute the line item would get at throttle 1, from the growth of served spend divided by the
throttle that produced it. It then sets

```
throttle = (remaining budget ÷ remaining time) ÷ yield
```

* Basing the target on *remaining* budget makes any earlier shortfall or overshoot correct itself
  (the integral action), with nothing to wind up.
* Dividing each observation by the throttle that caused it removes the feedback delay that makes a
  plain controller oscillate.
* Each tick may cut the throttle by up to 4× but only double it: overspending cannot be undone,
  underspending can be caught up.
* Catching up is capped at **2× the even rate**, so a line item that starts late in a period, or
  comes back from an outage, spends what it reasonably can instead of its whole budget in a burst.
* At a period boundary the pacer keeps the learned yield but **not the throttle**: the new period
  starts at the even rate for its fresh budget.
* Delivery up to the horizon is compared with what was served **15 s earlier**, the measured
  price-weighted mean time from serving an ad to playing it (a pod's ads play one after another),
  so ads still waiting to play are neither counted as delivered nor double-counted as in flight.

The controller was developed against a closed-loop simulation with the real system's lags
(`LineItemPacerTest`): minute totals reported ~70 s late, ads playing up to 30 s after they are
served, and 80% of served spend turning into delivery. Scenarios: steady traffic, traffic
tripling mid-period, traffic 30× what is needed, a budget needing 90% of all available traffic,
and a cold start 85% of the way into a period. Every full period delivers 98.9–99.2% of budget,
plus 0.3–0.5% that plays just after the boundary and counts towards the next period, with the
worst deviation from the even curve 0.8–5.9%. Learning the yield from delivered spend instead (the
first version) allowed deviations of up to 15%.

**What the live run caught that the first simulation did not.** The first full-stack run started
the stack 85% of the way into a period. Every pacer chased 85% of its budget at once, drove its
throttle to 1 and reported `UNDER_DELIVERING`; the next period inherited those throttles, and ads
served at throttle 1 just before the boundary played, and counted, in the next period. That period
overspent on 49 of 107 line items, by up to 51%, while the period after it, started cleanly, held
the median line item within 3% of the even curve. The simulation had started every run at a
period boundary and played every ad the instant it was served. Adding both effects reproduced the
overspend (`startingLateInAPeriodDoesNotOverspendTheNext`); the catch-up cap, the reset at
rollover and a serve-to-play allowance fixed it. The second live run then had no overspend but
delivered a uniform 94% of budget: the allowance, guessed at 30 s, was twice the real delay, so
already-delivered spend was also counted as in flight. Measuring the delay from the logs (each
impression joined to its auction) gave 15 s.

### Failure behaviour

* **Pacing service down or stale for 60 s:** the ad server falls back to a small fixed throttle
  (`DIRECT_PARTICIPATION`) and a hard stop once a line item's served spend reaches its budget.
  Delivery never exceeds what was served, so this cannot overspend; it may under-deliver for a
  while. (The first version stopped at budget ÷ 0.8, assuming 20% of served ads go unplayed. The
  live run measured 1–4%, which would have let the fallback overspend by about 20%.)
* **Redis down:** the same fallback, with the ad server's own served counter as the stop.
* **Traffic too thin for a budget:** the throttle reaches 1 and the line item reports
  `UNDER_DELIVERING`, instead of looking merely "behind".

## Phase 5 design: audit and observability

Two questions, answered separately:

* **Is it running?** Metrics and alerts. Prometheus scrapes every service, Flink (its Prometheus
  reporter) and Redpanda. 20 alert rules cover availability, ingest, ad serving, stream
  processing, pacing, Kafka and the audit itself.
* **Is the money right?** The audit service. A system can be up, fast and wrong. The audit
  compares independent records of the same fact, continuously, and pages when they disagree.

### The reconciliations

| Check | Compares | Expected | Over |
|---|---|---|---|
| `flink-vs-druid` | Flink's exactly-once minute totals (impressions, spend) per line item and minute, against Druid's rollup of the clean event stream | exactly equal | the latest 30 minutes that can no longer change (older than the 10-minute allowed lateness plus 2) |
| `served-vs-decision-log` | Served spend in Redis, which pacing steers by, against the sum of direct wins in the ad server's decision log | exactly equal | the last settled pacing period |
| `budget-compliance` | Delivered spend per line item (Druid) against its budget | ≤ 101% | the last settled period |
| `delivered-vs-served` | Played, valid spend against served spend | 85–102% | the last settled period |

The two sides of each check come from different code paths: Flink windows versus Druid
ingestion and SQL, and a Redis write versus a Kafka record. So a disagreement means one of them
is wrong. Each run is a metric (`audit_check_ok`, `audit_check_mismatches`), a record on
`audit-results.v1`, and an entry in the dashboard's audit panel, which names the line items that
disagree. A check whose source is down reports an error and keeps its last verdict;
`AuditNotCompleting` fires if nothing is verified for 20 minutes.

### Alerts reach your phone

```
Prometheus ─rules─► Alertmanager ─webhook (bearer token)─► dashboard ─Web Push (VAPID, encrypted)─► installed app
                     grouping, inhibition                    /api/alerts/webhook                     on each device
```

* **Turning on notifications.** In the app's Alerts panel, **Turn on notifications** subscribes
  that device. On iPhone this works once the app is on the Home Screen (iOS 16.4 or later).
* **Replacement.** A resolved alert replaces its own firing notification on the lock screen,
  because both carry the same tag.
* **Inhibition.** One cause, one notification. While the Flink job is down, its symptoms
  (stalled checkpoints, consumer lag, stale delivery data) are held back. The same applies to
  the pacing service: its symptoms are held back while it is down.
* **Push endpoints are restricted** to the browsers' push services, so subscribing cannot be
  used to make the server call internal addresses.
* **The webhook is internal.** It takes a shared token, and Caddy refuses it from outside.

Grafana is provisioned with one dashboard: health tiles, ingest, ad serving, stream processing,
pacing, the audit checks, and firing alerts.

## Hosting the dashboard as an app

The dashboard is an installable web app (PWA): from the browser it installs on Windows, macOS,
Linux, Android and iOS with its own icon and window, and opens offline. One server hosts both
the app and the API it reads, so there is a single HTTPS origin and nothing cross-site.

```
phone / desktop app ──HTTPS──► Caddy ──► dashboard (Nuxt server) ──► gateway, Flink, Druid,
     (PWA)               :443   TLS, HSTS  app + read-only API        ad server, pacing service
                                           sign-in required           (Compose network only)
```

### Deploy

On a host with Docker, about 10 GB of memory for the stack, and a DNS name pointing at it with
ports 80 and 443 open:

```bash
cp deploy/.env.example deploy/.env
cd dashboard && npm ci && npm run hash-password -- alice   # paste the line into DASHBOARD_USERS
openssl rand -base64 48                                    # paste into DASHBOARD_SESSION_SECRET
cd .. && docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
```

Caddy obtains and renews a Let's Encrypt certificate for `DASHBOARD_DOMAIN`. The overlay
publishes only ports 80 and 443. Kafka, Flink, Druid, the services, Prometheus, Alertmanager and
Grafana are reachable only inside the Compose network.

For alert notifications on phones, also set `VAPID_PUBLIC_KEY` and `VAPID_PRIVATE_KEY`
(`npx web-push generate-vapid-keys`) and `ALERT_WEBHOOK_TOKEN`. To open Grafana, use an SSH
tunnel to the host and point it at the `grafana` container, or publish it behind its own sign-in.

### Install

* **Desktop (Chrome, Edge) and Android:** an **Install app** button appears in the dashboard header
  (or use the install icon in the address bar).
* **iPhone and iPad:** in Safari, **Share → Add to Home Screen**. The **Install app** button shows
  these steps.

### Security model

| | |
|---|---|
| Sign-in | User names and scrypt password hashes in `NUXT_AUTH_USERS`; no sign-up, no database |
| Sessions | HMAC-signed cookie, `HttpOnly`, `Secure`, `SameSite=Strict`, 12 h. Changing a user's password, or the secret, ends their sessions |
| API | Read-only: every data route is `GET` and requires a session; anything else is 405 |
| Brute force | Failed logins limited to 20 per 15 min per client address and 10 per user name; unknown names take as long to reject as known ones |
| Client address | Taken from `X-Forwarded-For` only with `NUXT_TRUST_PROXY=true`, which the overlay sets because Caddy overwrites the header; spoofed headers were tested and do not evade the limit |
| CSRF | `SameSite=Strict`, JSON-only login, and a same-origin check on login |
| Fails closed | Sign-in on but users or secret missing → the API answers 503, it never serves data unauthenticated. Only `NUXT_AUTH_DISABLED=true` (the local default) turns it off |
| Offline data | The service worker caches the app shell only, never `/api/` responses, so no delivery or spend data stays on a device after signing out |
| Headers | HSTS (Caddy), `X-Frame-Options: DENY`, `frame-ancestors 'none'`, `nosniff`, `no-referrer`, `Cache-Control: no-store` on the API |

**Verified** against the full stack behind Caddy, over HTTPS:
* Every internal port is closed from outside, and HTTP redirects to HTTPS.
* Each data route returns 401 without a session and live data with one.
* A form-encoded or cross-origin login is refused, and a tampered cookie returns 401.
* The 21st failed login in a window returns 429, even with a different spoofed `X-Forwarded-For` on every attempt.

Playwright repeated sign-in, wrong-password feedback, service-worker activation, manifest
validity, offline launch, sign-out and the absence of cached API data at desktop and phone
sizes, in light and dark mode.

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

### Phase 4: budget pacing

Full stack at 2,000 events/s with 10-minute pacing periods (`PACING_PERIOD_MINUTES=10`,
`BUDGET_SCALE=0.33`): 107 line items, 12 of them guaranteed, $2,515.59 of budget per period.
Delivered spend is read back from Druid per line item and period, served spend from Redis and from
the decision log.

Two consecutive full periods of run 4, the final code (the stack started 3 minutes into the
period before them):

| Check | Period 1 | Period 2 |
|---|---|---|
| Line items over budget by more than 1% | **0** | **0** |
| Highest delivered / budget | 100.0% | 98.9% |
| Median delivered / budget | 96.9% | 96.4% |
| Line items within 95–101% of budget | 106 / 107 | 97 / 107 |
| Ad server served-spend counters (Redis) vs the decision log | **identical** ($2,406.02) | **identical** ($2,530.03) |

Delivery lands a few percent under budget by design: everything that can go wrong in the
estimate (late delivery data, ads not yet played, the delivered/served ratio) is resolved towards
stopping early, because an advertiser can be offered a make-good for under-delivery but cannot be
un-charged.

**What the three live runs found:**

| Run | Change before it | Over budget >1% | Median delivered / budget |
|---|---|---|---|
| 1 | (first version) | 49 of 107, worst 151% | 100.5% |
| 2 | catch-up cap, throttle reset at rollover, 30 s serve-to-play allowance | 0 | 94% |
| 3 | allowance set to the measured 15 s | 0 | 96.7% |
| 4 (table above) | stops assume every served ad plays until a ratio is observed, and in the fallback | 0 | 96.4–96.9% |

Run 1's overspend came from starting the stack late in a period (see the control law section).
Run 1's period after that one, started cleanly, was already within 3% of the even curve; the
controller was right, the cold start and rollover were not.

### Phase 5: audit and failure drills

Full stack with the production overlay (Caddy, sign-in), 2,000 events/s, 10-minute periods. A
stand-in "device" on the Compose network stood in for a phone: it subscribed through the real API,
checked each push's VAPID signature, and decrypted the payload with its own keys. It received
exactly what a phone would show.

| Drill | Detected by | Fault → notification on the device | Recovery → "Resolved" |
|---|---|---|---|
| Stop the pacing service | `ServiceDown` (its symptoms inhibited) | **87 s** | 56 s |
| Forge $5 of served spend in Redis for one line item | audit `served-vs-decision-log`, naming the line item and both amounts | **64 s** | 61 s |
| Kill the Flink TaskManager | `FlinkJobRestarting` (49 s), then `ServiceDown` | **49 s** | 57 s |

After the TaskManager kill, the audit compared the 30 settled minutes spanning the failure:
**2,381 line item minutes, $15,757.89 of spend, Flink and Druid exactly equal.** Exactly-once
held through the crash and restore. Across 30 consecutive audit rounds, every check stayed green
except during the deliberate forgery.

**What the audit found on its first real run:** the ad server's served-spend counters in Redis
were **3–6% low for all 107 line items**, and **21 line items had overspent**, in the period when
the stack started.
* **Cause.** The ad server incremented Redis on every win and gave up after waiting 20 ms for a
  connection. While everything warmed up, 4,744 increments were silently dropped in three minutes.
  Pacing then believed less had been spent than really was, and stopped late.
* **Fix.** Requests no longer touch Redis. Each instance keeps its own totals and a background
  thread writes them every 250 ms, as absolute values in a hash field per instance (`HSET`, not
  `INCRBY`). A failed or retried write can neither lose nor double-count spend.
* **Test.** `ServedLedgerTest` covers it, including a write failing just before a read.
* **On the next cold start:** counters equal to the decision log on all 107 line items, every line
  item within budget (highest 91.1%), and 0 Redis errors.

**Also fixed from the drills:**
* **A stale-checkpoint page during recovery.** Once the "Flink is down" alert resolved, it no
  longer held back the stale-checkpoint alert, which paged while the job was still restoring. The
  rule now gives a restarted job 2 minutes, and a `promtool` test covers it.
* **Fractional restart counts.** The restart alert said "3.007 times", because `increase()`
  extrapolates. It now rounds.
* **The checkpoint-age panel read "55 years".** Flink reports 0 before its first checkpoint, and
  the panel now ignores it.
* **The audit's Druid query used `minute` as a column alias.** That is a reserved word, and Druid
  returned 400.

### Phase 6: load tests

The whole stack on the same 4-vCPU machine, load generator included
([all numbers and how to reproduce them](docs/loadtest/RESULTS.md)):

| | Result |
|---|---|
| Event path, sustained | **20,000 events/s** (1.7 billion a day), audit exact |
| Above capacity (25–30k offered) | Flink is the bottleneck at about 20,500/s; the gateway keeps accepting, Kafka holds the backlog, nothing is lost |
| Full system, every ad break auctioned | **660 auctions/s**, decision p99 459 ms, nothing shed |
| Freshness | processed about 0.1 s after receipt; readable downstream 6–8 s later (p50), set by exactly-once commits on 10 s checkpoints |

**What it found:**
* **A full disk took the system down unannounced.** At 40,000 events/s, time-only retention
  filled the disk. Redpanda crashed, Flink restarted in a loop, and no alert named the cause.
  Topics are now capped by size as well as time, and three alerts were added or extended:
  `KafkaDiskLow` (on free bytes), `FlinkCheckpointsFailing` and `FlinkJobCrashLooping`.
* **A Flight Recorder profile showed 14% of Flink's CPU spent compressing small Kafka batches.**
  Larger batches cost no visible latency under exactly-once and gave +7% throughput.
* **Serialization between Flink's four keyed shuffles is the next lever,** at a quarter of its
  CPU.

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
| `DIRECT_PARTICIPATION`, `MAX_IN_FLIGHT`, `DSP_FREQUENCY_CAP_PER_HOUR` | ad server | `0.015` (fallback without pacing), `256`, `4` |
| `DASHBOARD_AUTH_DISABLED`, `DASHBOARD_USERS`, `DASHBOARD_SESSION_SECRET` | dashboard (local Compose) | `true`, empty, empty. In production set via `deploy/.env` (see Hosting) |
| `NUXT_SESSION_TTL_HOURS`, `NUXT_TRUST_PROXY` | dashboard | `12`, `false` (`true` in the production overlay) |
| `ALERT_WEBHOOK_TOKEN` | Alertmanager, dashboard | a fixed local value; required in production |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` | dashboard | empty: push notifications off |
| `AUDIT_INTERVAL_SECONDS` | audit service | `60` |
| `TOPIC_PARTITION_RETENTION_BYTES` | topic setup | `268435456` (256 MiB per partition, alongside 7-day time retention) |
| `SIM_AD_SERVER_URL` | simulator | `http://ad-server:8090`; empty plans ad breaks locally (event-path load tests) |
| `GRAFANA_ADMIN_PASSWORD`, `GRAFANA_ANONYMOUS` | Grafana | `admin`, `true` locally; password required and anonymous off in production |
| `PACING_PERIOD_MINUTES`, `BUDGET_SCALE` | ad server, pacing service | `30`, `1.0` (budgets sized for 30 min at ~2,000 events/s) |
| `PACING_TICK_SECONDS` | pacing service | `10` |
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
* CI also runs `promtool check config`, the alert-rule unit tests (`promtool test rules`, 10 cases)
  and `amtool check-config`.
* `.github/workflows/nuxtjs.yml` publishes a static build of the dashboard to GitHub Pages (enable
  Pages with source "GitHub Actions"). It has no server, so it talks to whichever gateway you
  give it with `?gateway=`, and the other panels say they need the stack. For the full app with
  sign-in, see [Hosting](#hosting-the-dashboard-as-an-app).
* The dashboard's unit tests (`npm test`, sign-in and session logic) run in CI, as does a check
  that the production overlay is valid.

## Known issues

* `npm audit` reports two high-severity advisories in the dashboard's build tooling (`braces`
  via nitropack's globby, `node-forge` via the dev server's listhen). Neither ships in the
  built dashboard, and no fixed versions exist yet.
* The ad server has a cold-start transient: in the first ~20 s after start, before the JIT and
  DSP connection pools warm up, some auctions time out or are shed (1,432 failed requests at the
  start of the measured run, then none).
* Pacing has a cold-start transient. A stack started part-way through a period has no traffic for
  its first ~40 s, so the pacer cannot learn a yield and probes upwards. Ads served by that probe
  play early in the next period, which in the measured run opened 55% ahead of its even curve,
  was 14% ahead by mid-period, and still finished under budget. Steady periods stay within ~5%.
* The pacing service keeps its state in memory and rebuilds it from Kafka on restart; with
  several ad server instances, served spend is shared through Redis but each instance sees the
  others' spend up to a second late.
* Dashboard sessions are stateless signed cookies. Signing out clears the cookie, but a copied
  cookie stays valid until it expires (12 h) unless the user's password or the session secret is
  changed. Login rate limits are in memory, per dashboard instance, and reset on restart.
* The per-user login limit means someone who knows a user name can lock that user out for 15
  minutes with wrong passwords. That trade-off favours resisting password guessing.
* Push delivery was verified end to end against a stand-in device that verifies VAPID and
  decrypts payloads. It was not verified against Google's, Mozilla's or Apple's push services,
  which this build environment cannot reach. Push subscriptions live in a JSON file on the
  dashboard's volume, which suits one dashboard instance.
* `FlinkJobRestarting` stays active for 10 minutes after a recovery, because it counts restarts
  in a 10-minute window.
* Druid's supervisor reports a lag of a few records per partition on the clean topic. Those are
  Kafka transaction markers, which a `read_committed` consumer never receives as records.
* Flink operator metrics (duplicates dropped, IVT flagged) restart from zero when the job
  restores; they are rates and health signals, not the source of truth. Counts come from the topics.
* Installing new dashboard dependencies with npm 10 hits an npm resolver bug with Nuxt ≥ 4.4.
  `npm ci` from the lockfile works. To change dependencies, use `npx npm@11 install ...`.
