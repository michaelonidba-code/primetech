# Ads Eventing: design and lessons

A real-time advertising event platform for a connected-TV ad tier: ad decisions, playback
tracking, stream processing, budget pacing, reporting and continuous audit. It runs on one
machine with Docker Compose, but every part is built the way it would be at scale and was
measured rather than assumed. This document is the short version: what was built, why it is
built that way, how it is known to be correct, how it fails, and what it would take to run it
at billions of events a day. The [README](../README.md) has the per-phase detail.

## The system in one picture

```
                         ┌──────── OpenRTB 2.6 (tmax 120 ms) ────────┐
                         ▼                                           │
                  3 mock DSPs                                        │
                                                                     │
 Player ──ad request──► Ad Server ── decision log ─────────────────► Kafka ─┐
   ▲   ◄──── ad pod ───  auction, caps, floors, pacing (Redis)              │
   │                       ▲            │ served spend                      │
   │                       │ throttle   ▼                                   │
   │                       └──── Redis ◄──── Pacing service ◄──────────┐    │
   │                                                                   │    │
   └─tracking events─► Ingest gateway ─► Kafka ─► Flink ─► Kafka ──────┴──► Druid ─► Dashboard (PWA)
                        validate, scrub   raw     dedup, enrich, IVT,   clean events,     ▲   │
                        ack when durable          minute totals         minute totals     │   │
                                                  (exactly-once)                          │   ▼
                                        Audit service: reconciles independent records ────┘  phones
                                        Prometheus ─► Alertmanager ─► dashboard ─► Web Push ──┘
```

| Component | Built with | Role |
|---|---|---|
| Ingest gateway | Java 21, virtual threads, Kafka idempotent producer | Validate events at the edge, pseudonymise device IDs, acknowledge only when Kafka has them |
| Event bus | Redpanda (Kafka API) | Durable, partitioned, replayable log between every stage |
| Stream processor | Flink 2.2 | De-duplicate, enrich, flag invalid traffic, minute totals per line item, exactly-once output |
| Analytics store | Druid 37 | Minute rollups with unique-device sketches, queried by SQL |
| Ad server | Java 21 | Direct demand plus an OpenRTB 2.6 auction across DSPs, frequency caps, pacing |
| Pacing service | Java 21 | One closed-loop controller per line item, fed by Flink's delivered spend |
| Audit service | Java 21 | Continuously reconciles independent records of the same money |
| Dashboard | Nuxt 4, installable PWA | Live view, sign-in, alerts with push notifications |
| Observability | Prometheus, Alertmanager, Grafana | 20 alert rules with unit tests, an operations dashboard |

## Decisions that shaped it

**At-least-once at the edge, exactly-once in the middle.** Players retry, networks duplicate,
and a gateway that dropped an event to avoid a duplicate would lose money. So the gateway
acknowledges a batch only when Kafka has every event (`acks=all`, idempotent producer), and
answers `503` otherwise; the player retries the whole batch. Duplicates are expected and
removed downstream by event ID, inside Flink's exactly-once state, which makes the pipeline
effectively-once end to end. The trade-off is state: 30 minutes of event IDs per key.

**Validate one event at a time, never fail a batch for one bad event.** Rejected events go to a
dead-letter topic with the reason, scrubbed of device IDs. Batches stay cheap to retry.

**Privacy at the first hop.** Raw device IDs never reach Kafka: the gateway replaces them with a
keyed hash when the viewer consented and drops them when not. Every downstream system works
with pseudonyms only, and rotating the key unlinks history.

**Event time, with lateness as a first-class input.** Delivery is counted in the minute the ad
played, not the minute the event arrived. Windows accept events up to 10 minutes late and
re-emit corrected totals (revisions); a custom trigger coalesces late firings so one minute is
revised at most every 5 seconds instead of once per late event (217 revisions became 6).

**Invalid traffic is flagged, not deleted.** A device above a plausible impression rate is
marked, excluded from spend and kept for analysis. Billing disputes need the evidence.

**One schema of record, two stores of record.** Kafka topics are the system of record; Druid
and Redis are views that can be rebuilt. Services that keep state (pacing, audit) rebuild it
from topics on start, so there is nothing to back up beyond Kafka.

**The ad server degrades instead of collapsing.** Requests are admitted up to a bound and
refused immediately beyond it (a fast `503` lets the player fill the break with a fallback).
The auction deadline is separate from the HTTP client's lifetime, so a slow DSP costs one bid,
not a connection. Before these, overload made it hang with 13,062 connections stuck.

**Pacing from two clocks.** Delivered spend (Flink) is exact but about a minute old; served
spend (ad server) is immediate but overstates delivery. The pacer budgets with delivered spend,
steers with served spend, and publishes a stop threshold in served spend so the ad server stops
a line item the moment it is spent. The controller learns each line item's yield instead of
running a PID on spend, which removes the delay that makes naive controllers oscillate. Every
uncertainty is resolved towards stopping early: under-delivery can be made good, overspend
cannot be un-charged.

**Shared counters as absolute values.** Each ad server instance writes its own cumulative total
per line item (`HSET`, one field per instance) in the background, and readers sum the fields. A
failed, slow or retried write can neither lose nor double-count spend. (The first version
incremented Redis per request and silently dropped increments under load; see below.)

**Prove the money continuously.** A system can be up, fast and wrong. The audit service compares
records of the same fact produced by different code: Flink's totals with Druid's, Redis with the
decision log, delivery with budgets. It pages when they disagree, with the line items named.

**One origin for the app.** The dashboard server hosts the installable app and a read-only,
signed-in API on the same origin behind Caddy: no CORS, `SameSite=Strict` cookies, and only
ports 80 and 443 published. The service worker never caches API responses, so no spend data
stays on a shared device.

## How correctness is known

Every phase ended with a reconciliation that had to match exactly, not approximately:

| What | Result |
|---|---|
| Clean events vs simulator ground truth (duplicates, late and invalid events injected) | 636,872 = 636,872 |
| Exactly-once through a TaskManager `kill` mid-run | 956,446 = 956,446 unique events, 0 missing, 0 duplicated |
| Druid vs the clean topic | identical events, types, IVT and billable spend |
| Impressions joined to their auctions at the clearing price | 44,610 / 44,610 |
| Ad server served-spend counters vs its decision log, per period | identical to the cent |
| Flink minute totals vs Druid, continuously, across a TaskManager kill | 2,381 line item minutes identical |

Unit tests (116 Java, 7 TypeScript, 10 alert-rule cases) cover the logic; these reconciliations
cover the system. Under load, up to the point where the machine saturates and beyond, the audit's
Flink-versus-Druid check stayed exact at every step.

## How it fails

| Failure | Behaviour | Detected by |
|---|---|---|
| Kafka unavailable | Gateway answers 503, players retry; nothing acknowledged is lost | `IngestPublishFailures`, `KafkaPartitionsUnavailable` |
| Flink TaskManager dies | Job restores from the last checkpoint; output stays exactly-once; delivery data is delayed | `FlinkJobRestarting`, `ServiceDown` (49 s to a phone notification) |
| Pacing service down | Ad server falls back to a small fixed throttle and a hard stop at budget: under-delivers, cannot overspend | `ServiceDown`, symptoms inhibited (87 s) |
| Redis down or slow | Served spend is kept in each ad server and written when Redis returns; pacing falls back as above | `AdServerOnPacingFallback` |
| A DSP slow or broken | Its bids miss the deadline; the auction runs without it | DSP timeout panel, `FillRateLow` |
| Ad server overloaded | Admission control sheds with an immediate 503 | `AdServerShedding` |
| A counter or total silently wrong | Audit mismatch, naming the line items | `AuditMismatch` (64 s) |
| Audit cannot read a source | Keeps its last verdict, reports the error | `AuditNotCompleting` |
| Disk fills (found in the load test) | Redpanda stops, Flink cannot checkpoint and restarts in a loop; topics are now capped by size as well as time | `KafkaDiskLow`, `FlinkCheckpointsFailing`, `FlinkJobCrashLooping` |
| Sustained input above capacity | The gateway keeps accepting, Kafka holds the backlog, Flink catches up when load drops; nothing is lost | `FlinkConsumerLagHigh`, `DeliveryDataStale` |

## Bugs that only measurement found

None of these failed a unit test; each was found by reconciling or measuring the running
system, and each now has a test:

1. **Late-window spam.** One minute was revised 217 times by late events. Fixed with a coalescing trigger.
2. **Flink lost event time** on records released from broadcast state, so they missed their windows.
3. **A replayed run looked like data loss.** `SEED` was not passed to the simulator, so event IDs repeated and were correctly de-duplicated.
4. **The ad server hung under overload**: unbounded work, 13,062 abandoned connections, file descriptors exhausted.
5. **The campaign catalog was never published** (`max.block.ms=0` failed every send until metadata arrived); 82k events went unenriched.
6. **Second-price auctions overcharged** sole bidders by a cent.
7. **Pacing overspent by up to 51% after a cold start**, from chasing a partial period and carrying a throttle across the boundary.
8. **A guessed constant under-delivered by 6%**: serve-to-play delay assumed 30 s, measured 15 s.
9. **The pacing fallback could have overspent by 20%**: it assumed 20% of served ads go unplayed; the live figure was 1–4%.
10. **Served-spend increments were dropped under load**: counters 3–6% low and 21 line items over budget, found by the audit on its first run.
11. **A stale-checkpoint page during recovery**, after inhibition lifted.
12. **A full disk took the system down without an alert naming it.** Topics were retained by time
    only, so a load test filled the disk; Redpanda crashed and Flink restarted in a loop. The
    restart grace from item 11 also hid the crash loop. Now: size-based retention, a disk alert on
    free bytes, and critical alerts for failing checkpoints and crash loops.
13. **Flink spent 14% of its CPU compressing tiny Kafka batches** (found with a Flight Recorder
    profile). Larger batches cost no visible latency under exactly-once: +7% throughput.

## Capacity

Measured on one 4-vCPU machine running everything ([full results](loadtest/RESULTS.md)):

| | Result |
|---|---|
| Event path, sustained | **20,000 events/s** (1.7 billion a day): gateway, Kafka, Flink exactly-once, Druid, audit exact |
| Above that | Flink is the bottleneck; the gateway keeps accepting and Kafka holds the backlog, nothing is lost |
| Full system, every ad break auctioned | **660 auctions/s**, p99 459 ms, nothing shed, with three DSPs on the same cores |
| Freshness | processed about 0.1 s after receipt; visible downstream 6–8 s later (p50), set by the 10 s checkpoint that commits exactly-once output |

Per core, from the load tests' CPU samples at saturation and rounded down:

| Stage | Throughput per core | Note |
|---|---|---|
| Ingest gateway | 50,000 events/s | measured higher; JSON parse, validate, hash, produce |
| Kafka (Redpanda) | 50,000 events/s | writes plus Flink's and Druid's reads |
| Flink job | 7,500 events/s | four keyed shuffles, RocksDB dedup state, exactly-once sinks |
| Druid ingestion | 40,000 events/s | minute rollup |
| Ad server | ~750 auctions/s | estimate: the measured 375/s per core included the mock DSPs |

**Sizing for 10 billion events a day.** Assume a 3× daily peak: 350,000 events/s, and
0.12 auctions per event (measured) = 42,000 auctions/s.

| Stage | Cores at peak | Other resources |
|---|---|---|
| Gateway | 7 | stateless; scale behind a load balancer |
| Kafka | 7 | ~0.6 KB per event per topic as JSON; raw + clean ≈ 12 TB/day before compression and replication: tiered storage to object storage, 96+ partitions |
| Flink | 47 | de-duplication state: 30 min × 350k/s ≈ 630 M event IDs, tens of GB in RocksDB across the cluster |
| Druid ingestion | 9 | plus historicals sized by query load and retention |
| Ad server | 56 | regional, close to players; DSP calls dominate latency |
| **Total** | **~125** | **~250 with 2× for headroom and zone redundancy: about 32 machines of 8 vCPU** |

This is an order-of-magnitude model from one small machine, with the assumptions above. The
cheapest improvements before buying hardware: fewer shuffles in the Flink job (serialization
between operators is a quarter of its CPU), a binary format (Avro or Protobuf) instead of JSON,
and running the ad server and DSP calls on their own machines.

## What production would add

* **Multi-node everything.** Kafka with replication factor 3 across zones, Flink with HA job
  managers and remote checkpoint storage (S3), Druid with deep storage, Redis with replicas.
* **Schemas as contracts.** A schema registry with compatibility rules instead of JSON with a
  version field; Avro or Protobuf on the wire roughly halves bytes per event.
* **Ad server at the edge.** Regional ad servers near players, frequency caps in a regional
  store, budgets reconciled globally by the pacing service.
* **Billing-grade reporting.** Daily batch recomputation from the raw topic (or a lakehouse
  copy) as the invoice of record, with the streaming totals as the fast estimate, reconciled.
* **Identity and access.** SSO for the dashboard instead of local users, per-advertiser data access.
* **Load tests on real hardware.** The numbers below come from one 4-vCPU machine running all
  twenty-odd containers; the per-core figures matter more than the totals.
