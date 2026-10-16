#!/usr/bin/env python3
"""Renders load test results (from run.py) as Markdown tables.

    python3 loadtest/report.py docs/loadtest/events.json docs/loadtest/full.json > docs/loadtest/RESULTS.md
"""
import json
import sys

# Containers grouped by the stage of the pipeline they belong to.
STAGES = {
    "load generator": ["simulator"],
    "gateway": ["gateway"],
    "Kafka": ["redpanda"],
    "Flink": ["jobmanager", "taskmanager"],
    "Druid": ["coordinator", "broker", "historical", "middlemanager", "router", "zookeeper", "postgres"],
    "ad serving": ["ad-server", "dsp-premium", "dsp-budget", "dsp-slow", "redis"],
    "pacing, audit, observability": ["pacing-service", "audit-service", "prometheus", "alertmanager", "grafana", "dashboard"],
}


def num(v, digits=0, suffix=""):
    if v is None:
        return "–"
    return f"{v:,.{digits}f}{suffix}"


def keeps_up(r):
    growth = r.get("flink_pending_growth_per_s") or 0
    return growth < 0.01 * r["gateway_eps"]


def table(results):
    full = results[0]["mode"] == "full"
    head = ["Offered events/s", "Gateway accepted", "Gateway p99", "Flink processed", "Flink backlog growth",
            "Committed output visible p50 / p99", "Keeps up"]
    if full:
        head[4:4] = ["Auctions/s", "Decision p99", "Shed"]
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for r in results:
        f = r["freshness"]
        row = [num(r["target_eps"]), num(r["gateway_eps"], suffix="/s"), num(r["gateway_p99_ms"], suffix=" ms"),
               num(r["flink_in_eps"], suffix="/s"), num(r["flink_pending_growth_per_s"], suffix="/s"),
               f"{num((f['visible_p50_ms'] or 0) / 1000, 1)} / {num((f['visible_p99_ms'] or 0) / 1000, 1)} s",
               "yes" if keeps_up(r) else "**no**"]
        if full:
            row[4:4] = [num(r["auctions_per_s"], suffix="/s"), num(r["decision_p99_ms"], suffix=" ms"),
                        num(100 * (r["shed_ratio"] or 0), 1, "%")]
        lines.append("| " + " | ".join(row) + " |")
    return "\n".join(lines)


def cpu_table(results):
    head = ["Offered events/s"] + list(STAGES) + ["total (of 400%)"]
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for r in results:
        c = r["cpu_percent"]
        cells = [sum(c.get(n, 0) for n in names) for names in STAGES.values()]
        lines.append("| " + " | ".join([num(r["target_eps"])] + [num(v, suffix="%") for v in cells]
                                       + [num(sum(c.values()), suffix="%")]) + " |")
    return "\n".join(lines)


def main():
    for path in sys.argv[1:]:
        results = json.load(open(path))
        mode = results[0]["mode"]
        print(f"### {'Event path only' if mode == 'events' else 'Full system (every ad break auctioned)'}\n")
        print(table(results) + "\n")
        print("CPU by stage (one `docker stats` sample mid-window, so ±20%):\n")
        print(cpu_table(results) + "\n")


if __name__ == "__main__":
    main()
