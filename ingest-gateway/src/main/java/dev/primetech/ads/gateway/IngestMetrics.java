package dev.primetech.ads.gateway;

import dev.primetech.ads.common.EventType;
import dev.primetech.ads.common.RejectReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Gateway counters. Backed by Micrometer so Prometheus scrapes the same numbers the dashboard
 * reads from {@code /stats}. Accepted and rejected are separate metric names because Prometheus
 * requires every series of one metric to have the same label keys.
 */
public final class IngestMetrics {
    private final Map<EventType, Counter> accepted = new EnumMap<>(EventType.class);
    private final Map<RejectReason, Counter> rejected = new EnumMap<>(RejectReason.class);
    private final Counter publishFailures;
    private final Counter batches;
    private final Timer requestLatency;
    private final long startedAtMs = System.currentTimeMillis();

    public IngestMetrics(MeterRegistry registry) {
        for (EventType type : EventType.values()) {
            accepted.put(type, Counter.builder("ads.ingest.events.accepted")
                    .description("Events accepted and durably written to Kafka")
                    .tag("type", type.name())
                    .register(registry));
        }
        for (RejectReason reason : RejectReason.values()) {
            rejected.put(reason, Counter.builder("ads.ingest.events.rejected")
                    .description("Events rejected by validation and sent to the DLQ")
                    .tag("reason", reason.name())
                    .register(registry));
        }
        publishFailures = Counter.builder("ads.ingest.publish.failures")
                .description("Batches answered with 503 because Kafka did not acknowledge every event")
                .register(registry);
        batches = Counter.builder("ads.ingest.batches").register(registry);
        requestLatency = Timer.builder("ads.ingest.request.latency")
                .description("End-to-end latency of POST /v1/events, including the Kafka ack")
                .publishPercentiles(0.5, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void accepted(EventType type) {
        accepted.get(type).increment();
    }

    public void rejected(RejectReason reason) {
        rejected.get(reason).increment();
    }

    public void publishFailed() {
        publishFailures.increment();
    }

    public void batch() {
        batches.increment();
    }

    public void recordLatency(long nanos) {
        requestLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public Snapshot snapshot() {
        Map<String, Long> byType = new LinkedHashMap<>();
        long acceptedTotal = 0;
        for (var e : accepted.entrySet()) {
            long n = (long) e.getValue().count();
            byType.put(e.getKey().name(), n);
            acceptedTotal += n;
        }
        Map<String, Long> byReason = new LinkedHashMap<>();
        long rejectedTotal = 0;
        for (var e : rejected.entrySet()) {
            long n = (long) e.getValue().count();
            byReason.put(e.getKey().name(), n);
            rejectedTotal += n;
        }
        double p50 = 0, p99 = 0;
        for (ValueAtPercentile v : requestLatency.takeSnapshot().percentileValues()) {
            if (v.percentile() == 0.5) {
                p50 = v.value(TimeUnit.MILLISECONDS);
            } else if (v.percentile() == 0.99) {
                p99 = v.value(TimeUnit.MILLISECONDS);
            }
        }
        return new Snapshot(System.currentTimeMillis(), (System.currentTimeMillis() - startedAtMs) / 1000,
                acceptedTotal, rejectedTotal, (long) batches.count(), (long) publishFailures.count(),
                byType, byReason, p50, p99);
    }

    public record Snapshot(
            long timestampMs,
            long uptimeSeconds,
            long accepted,
            long rejected,
            long batches,
            long publishFailures,
            Map<String, Long> acceptedByType,
            Map<String, Long> rejectedByReason,
            double latencyP50Ms,
            double latencyP99Ms) {
    }
}
