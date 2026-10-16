# Load test results

All runs: the whole stack (about 27 containers, including Druid's seven and the observability
services) on one 4-vCPU, 16 GB machine, sharing it with the load generator. Each step: 60 s of
warm-up, then a 120 s measurement window read from Prometheus. Reproduce with:

```bash
docker compose up -d $(docker compose config --services | grep -v simulator)
python3 loadtest/run.py --mode events --steps 5000,10000,20000,25000,30000 --out docs/loadtest/events.json
python3 loadtest/run.py --mode full --steps 2000,4000,6000 --out docs/loadtest/full.json
python3 loadtest/report.py docs/loadtest/events.json docs/loadtest/full.json
```

"Keeps up" means Flink's backlog grew by less than 1% of the input rate. "Committed output
visible" is measured by reading the clean topic with `read_committed` as it is written: the time
from the gateway receiving an event to it being readable downstream.

### Event path only

| Offered events/s | Gateway accepted | Gateway p99 | Flink processed | Flink backlog growth | Committed output visible p50 / p99 | Keeps up |
|---|---|---|---|---|---|---|
| 5,000 | 4,998/s | 27 ms | 4,971/s | 0/s | 5.7 / 10.6 s | yes |
| 10,000 | 9,867/s | 167 ms | 9,873/s | 0/s | 6.2 / 10.9 s | yes |
| 20,000 | 20,083/s | 511 ms | 20,346/s | 0/s | 7.7 / 11.5 s | yes |
| 25,000 | 24,703/s | 479 ms | 20,513/s | 3,789/s | 13.0 / 19.5 s | **no** |
| 30,000 | 29,601/s | 783 ms | 18,218/s | 10,944/s | 27.1 / 36.7 s | **no** |

CPU by stage (one `docker stats` sample mid-window, so ±20%):

| Offered events/s | load generator | gateway | Kafka | Flink | Druid | ad serving | pacing, audit, observability | total (of 400%) |
|---|---|---|---|---|---|---|---|---|
| 5,000 | 10% | 15% | 24% | 67% | 20% | 3% | 5% | 144% |
| 10,000 | 16% | 22% | 37% | 101% | 5% | 3% | 3% | 187% |
| 20,000 | 18% | 25% | 26% | 260% | 29% | 2% | 2% | 364% |
| 25,000 | 24% | 28% | 24% | 277% | 3% | 3% | 2% | 361% |
| 30,000 | 20% | 25% | 29% | 108% | 38% | 2% | 2% | 224% |

### Full system (every ad break auctioned)

| Offered events/s | Gateway accepted | Gateway p99 | Flink processed | Auctions/s | Decision p99 | Shed | Flink backlog growth | Committed output visible p50 / p99 | Keeps up |
|---|---|---|---|---|---|---|---|---|---|
| 2,000 | 1,928/s | 147 ms | 1,931/s | 230/s | 154 ms | 0.0% | 0/s | 6.1 / 11.4 s | yes |
| 4,000 | 3,621/s | 360 ms | 3,597/s | 452/s | 321 ms | 0.0% | -7/s | 6.9 / 12.2 s | yes |
| 6,000 | 4,417/s | 515 ms | 4,391/s | 664/s | 459 ms | 0.0% | 0/s | 7.0 / 12.5 s | yes |

CPU by stage (one `docker stats` sample mid-window, so ±20%):

| Offered events/s | load generator | gateway | Kafka | Flink | Druid | ad serving | pacing, audit, observability | total (of 400%) |
|---|---|---|---|---|---|---|---|---|
| 2,000 | 17% | 12% | 38% | 119% | 7% | 106% | 12% | 313% |
| 4,000 | 27% | 12% | 33% | 109% | 7% | 147% | 12% | 346% |
| 6,000 | 28% | 12% | 41% | 98% | 6% | 177% | 6% | 368% |

## Reading the numbers

* **The event path sustains 20,000 events/s on this machine** (1.7 billion a day). Above that,
  Flink is the bottleneck: at 25,000 it processes about 20,500 and its backlog grows.
* **Overload is absorbed, not lost.** The gateway kept accepting everything offered (with
  rising latency) and the excess waited in Kafka: no publish failures at any step (the 0.5% of
  events rejected are the simulator's deliberately invalid ones), and the audit's
  Flink-versus-Druid check stayed exact at every step.
* **Freshness is set by exactly-once.** Flink processes an event about 0.1 s after the gateway
  receives it at up to 10,000 events/s (0.6 s p50, 2.7 s p99 at 20,000, as it nears
  saturation), but its output is committed with each checkpoint (every 10 s), so
  consumers see it 6–8 s later at p50 and about 11 s at p99. A shorter checkpoint interval
  trades CPU and small transactions for freshness; at-least-once output would be near-instant
  but would need idempotent consumers.
* **The full system tops out near 660 auctions/s** with every ad break auctioned against three
  mock DSPs, all on the same four cores. Ad serving then uses more CPU than the whole event
  pipeline; no request was shed, and decision latency rose to p99 459 ms. In the full-system
  runs the simulated players wait for each ad decision before playing it, so the generator slows
  with the ad server, and offered load above about 4,400 events/s is not reached.

* **In event-only runs `LineItemOverBudget` fires, correctly.** There the simulator picks ads
  itself instead of asking the ad server, so nothing paces the direct line items and they
  overspend: an unplanned end-to-end check that the alert catches spend that bypasses pacing.

## What the load test found

1. **The disk filled, and the system went down in a way the alerts did not name.** At 40,000
   events/s the topics, retained only by time (7 days), grew by several GB a minute. Redpanda
   crashed on an assertion, Flink's RocksDB could not write checkpoints and the job restarted in a
   loop. Alerts fired for the symptoms (`FlinkJobRestarting`, `DeliveryDataStale`,
   `AuditNotCompleting`) but none for the cause, and the restart grace added in Phase 5 kept
   `FlinkCheckpointsStalled` silent in a crash loop. Fixes: retention by size as well as time
   (`TOPIC_PARTITION_RETENTION_BYTES`), `KafkaDiskLow` on free bytes rather than only Redpanda's
   own flag, and new critical alerts `FlinkCheckpointsFailing` and `FlinkJobCrashLooping`, each
   with `promtool` tests.
2. **Flink spent 14% of its CPU compressing small Kafka batches.** A Flight Recorder profile of
   the TaskManager at saturation showed LZ4 in the transactional sinks, much of it writing the
   headers of 16 KB batches. Records are only visible when the transaction commits, so letting
   batches fill (`linger.ms` 50, `batch.size` 256 KB) costs no visible latency. On identical fresh
   stacks at 30,000 events/s: 25,293 → 26,979 processed (+7%, one run each), backlog growth
   4,400 → 2,683/s, TaskManager CPU 181% → 127%.
3. **The remaining Flink cost is serialization between operators** (about 25% of samples): each
   event crosses four keyed shuffles (event ID, campaign, device, line item) and is serialized
   whole each time. Fewer shuffles, or slimmer records after enrichment, is the next lever; it is
   a restructuring, not a setting, so it is left as the first item for scaling work.
4. **Throughput depends on state size.** A job that had accumulated 30 minutes of de-duplication
   state processed 17,500 events/s at the 30,000 step; a fresh one 25,000–27,000. Capacity
   planning should use the steady-state figure.
