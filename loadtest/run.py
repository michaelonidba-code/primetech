#!/usr/bin/env python3
"""Steps the full stack through rising load and records what each component does.

Each step restarts the simulator at a target rate, waits for things to settle, then measures a
window. Everything comes from places an operator would look: Prometheus (every service, Flink,
Redpanda), `docker stats` (CPU per container), the clean topic (how fresh committed output is),
Druid's supervisor (ingestion lag) and the audit service (is the money still right).

    python3 loadtest/run.py --mode events --steps 5000,10000,20000 --out docs/loadtest/events.json

Modes:
  events  players send tracking events only: the ingest -> Kafka -> Flink -> Druid path
  full    every ad break is also requested from the ad server (auction, caps, pacing)

Needs the stack running with its ports published (plain `docker compose up`, not the
production overlay) and nothing else loading the machine.
"""
import argparse
import json
import os
import statistics
import subprocess
import threading
import time
import urllib.parse
import urllib.request

PROM = "http://localhost:9090"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def prom(query, at=None):
    """Instant query; returns the first sample's value as float, or None."""
    params = {"query": query}
    if at:
        params["time"] = str(at)
    with urllib.request.urlopen(f"{PROM}/api/v1/query?{urllib.parse.urlencode(params)}", timeout=20) as r:
        result = json.load(r)["data"]["result"]
    if not result:
        return None
    v = float(result[0]["value"][1])
    return None if v != v else v  # NaN -> None


def compose(*args, env=None):
    subprocess.run(["docker", "compose", *args], cwd=ROOT, check=True, env={**os.environ, **(env or {})},
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def start_simulator(eps, mode, seed):
    # More in-flight batches at higher rates, so the generator is never what limits throughput.
    concurrency = max(16, int(eps / 1000) * 4)
    compose("rm", "-sf", "simulator")
    compose("up", "-d", "--no-deps", "simulator", env={
        "TARGET_EPS": str(eps), "CONCURRENCY": str(concurrency), "DURATION_SECONDS": "0", "SEED": str(seed),
        "SIM_AD_SERVER_URL": "http://ad-server:8090" if mode == "full" else "",
    })


def cpu_by_container():
    out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}"],
                         capture_output=True, text=True, check=True).stdout
    cpu = {}
    for line in out.strip().splitlines():
        name, pct = line.split("\t")
        cpu[name.removeprefix("ads-eventing-").removesuffix("-1")] = float(pct.rstrip("%"))
    return cpu


def freshness(seconds):
    """Reads the clean topic as it is committed and measures, per record, how long after the
    gateway received the event it became readable (read_committed: Flink's transactions commit on
    each checkpoint). Also returns the gateway -> Flink processing part of that delay."""
    proc = subprocess.Popen(["docker", "compose", "exec", "-T", "redpanda", "rpk", "topic", "consume",
                             "ad-events-clean.v1", "-o", "end", "--read-committed", "-f", "%v\\n"],
                            cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    visible, processing = [], []
    stop = time.time() + seconds

    def read():
        for line in proc.stdout:
            now_ms = time.time() * 1000
            try:
                e = json.loads(line)
            except ValueError:
                continue
            visible.append(now_ms - e["receivedTimeMs"])
            processing.append(e["processedTimeMs"] - e["receivedTimeMs"])

    t = threading.Thread(target=read, daemon=True)
    t.start()
    time.sleep(max(0, stop - time.time()))
    proc.kill()
    t.join(timeout=5)

    def q(xs, p):
        return round(statistics.quantiles(xs, n=100)[p - 1]) if len(xs) > 100 else None

    return {"samples": len(visible), "visible_p50_ms": q(visible, 50), "visible_p99_ms": q(visible, 99),
            "processing_p50_ms": q(processing, 50), "processing_p99_ms": q(processing, 99)}


def druid_lag():
    try:
        with urllib.request.urlopen("http://localhost:8888/druid/indexer/v1/supervisor/ad_events/status", timeout=10) as r:
            payload = json.load(r)["payload"]
        return payload.get("aggregateLag")
    except Exception:  # noqa: BLE001 - lag is informative, not essential
        return None


def audit():
    try:
        with urllib.request.urlopen("http://localhost:8096/stats", timeout=10) as r:
            checks = json.load(r)["checks"]
        return {k: (v.get("latestCompleted") or v.get("latest") or {}).get("status") for k, v in checks.items()}
    except Exception:  # noqa: BLE001
        return None


def measure(eps, mode, window):
    t0 = time.time()
    fresh = freshness(min(60, window))
    time.sleep(max(0, window / 2 - (time.time() - t0)))
    cpu = cpu_by_container()
    time.sleep(max(0, window - (time.time() - t0)))
    w = f"{window}s"
    pending_now = prom('sum(flink_taskmanager_job_task_operator_pendingRecords{operator_name=~".*ad_events.*"})')
    pending_before = prom(f'sum(flink_taskmanager_job_task_operator_pendingRecords{{operator_name=~".*ad_events.*"}} offset {w})')
    r = {
        "target_eps": eps,
        "mode": mode,
        "gateway_eps": prom(f"sum(rate(ads_ingest_events_accepted_total[{w}]))"),
        "gateway_rejected_eps": prom(f"sum(rate(ads_ingest_events_rejected_total[{w}]))"),
        "gateway_p50_ms": ms(prom(f"histogram_quantile(0.5, sum by (le) (rate(ads_ingest_request_latency_seconds_bucket[{w}])))")),
        "gateway_p99_ms": ms(prom(f"histogram_quantile(0.99, sum by (le) (rate(ads_ingest_request_latency_seconds_bucket[{w}])))")),
        "publish_failures": prom(f"sum(increase(ads_ingest_publish_failures_total[{w}]))"),
        # Counter rate over the same window as the gateway's (Flink's per-second meters lag).
        "flink_in_eps": prom(f'sum(rate(flink_taskmanager_job_task_operator_numRecordsOut{{operator_name="Source:_ad_events"}}[{w}]))'),
        "flink_pending": pending_now,
        "flink_pending_growth_per_s": None if pending_now is None or pending_before is None else (pending_now - pending_before) / window,
        "flink_backpressured_tasks": prom(f"max_over_time(sum(flink_taskmanager_job_task_isBackPressured)[{w}:15s])"),
        "checkpoint_ms": prom(f"max_over_time(max(flink_jobmanager_job_lastCheckpointDuration)[{w}:15s])"),
        "druid_lag_records": druid_lag(),
        "auctions_per_s": prom(f"sum(rate(adserver_requests_total[{w}]))") if mode == "full" else None,
        "decision_p99_ms": ms(prom(f"histogram_quantile(0.99, sum by (le) (rate(adserver_decision_latency_seconds_bucket[{w}])))")) if mode == "full" else None,
        "shed_ratio": prom(f"sum(rate(adserver_requests_shed_total[{w}])) / sum(rate(adserver_requests_total[{w}]))") if mode == "full" else None,
        "freshness": fresh,
        "cpu_percent": cpu,
        "audit": audit(),
        "firing_alerts": prom('count(ALERTS{alertstate="firing"})') or 0,
    }
    return r


def ms(seconds):
    return None if seconds is None else round(seconds * 1000, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["events", "full"], default="events")
    ap.add_argument("--steps", default="2000,5000,10000,20000")
    ap.add_argument("--warmup", type=int, default=60)
    ap.add_argument("--window", type=int, default=120)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    results = []
    for i, eps in enumerate(int(s) for s in args.steps.split(",")):
        print(f"[{time.strftime('%H:%M:%S')}] {args.mode} {eps} events/s: warming up {args.warmup}s", flush=True)
        start_simulator(eps, args.mode, seed=1000 + i)
        time.sleep(args.warmup)
        r = measure(eps, args.mode, args.window)
        results.append(r)
        print(json.dumps({k: v for k, v in r.items() if k not in ("cpu_percent",)}), flush=True)
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w") as f:
            json.dump(results, f, indent=1)
    compose("rm", "-sf", "simulator")


if __name__ == "__main__":
    main()
