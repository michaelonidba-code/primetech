package dev.primetech.ads.audit;

import com.fasterxml.jackson.databind.JsonNode;
import dev.primetech.ads.common.LineItemMinute;
import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.pacing.PacingPeriod;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the audit has read from Kafka: Flink's minute totals (latest revision of each), direct spend
 * per period and line item from the ad server's decision log, and line item budgets. Bounded:
 * only recent minutes and periods are kept.
 */
public final class AuditLedger {
    static final long KEEP_MINUTES_MS = 3 * 3_600_000L;
    static final int KEEP_PERIODS = 12;

    private final PacingPeriod period;
    private final Map<String, LineItemMinute> minutes = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, AtomicLong>> servedByPeriod = new ConcurrentHashMap<>();
    private final Map<String, LineItemConfig> lineItems = new ConcurrentHashMap<>();
    private final AtomicLong latestDecisionMs = new AtomicLong(Long.MIN_VALUE);

    public AuditLedger(PacingPeriod period) {
        this.period = period;
    }

    public void minute(LineItemMinute m) {
        minutes.merge(m.key(), m, (old, now) -> now.revision() >= old.revision() ? now : old);
    }

    public void lineItem(LineItemConfig config) {
        lineItems.put(config.lineItemId(), config);
    }

    /** One record from the decision log: counts direct wins at the price the ad server charged. */
    public void decision(JsonNode d) {
        long ts = d.path("timestampMs").asLong();
        latestDecisionMs.accumulateAndGet(ts, Math::max);
        for (JsonNode slot : d.path("slots")) {
            if ("direct".equals(slot.path("source").asText()) && slot.hasNonNull("lineItemId")) {
                // The ad server charges clearing CPM / 1000 per impression, rounded down.
                long price = slot.path("clearingCpmMicros").asLong() / 1000;
                servedByPeriod.computeIfAbsent(period.index(ts), k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(slot.path("lineItemId").asText(), k -> new AtomicLong())
                        .addAndGet(price);
            }
        }
    }

    /** Flink totals for minutes starting in [from, to). */
    public Map<String, Checks.Totals> minutes(long from, long to) {
        var out = new HashMap<String, Checks.Totals>();
        for (LineItemMinute m : minutes.values()) {
            if (m.windowStartMs() >= from && m.windowStartMs() < to) {
                out.put(m.key(), new Checks.Totals(m.impressions(), m.spendMicros()));
            }
        }
        return out;
    }

    public Map<String, Long> served(long periodIndex) {
        var out = new HashMap<String, Long>();
        servedByPeriod.getOrDefault(periodIndex, Map.of()).forEach((li, v) -> out.put(li, v.get()));
        return out;
    }

    public Map<String, Long> budgets() {
        var out = new HashMap<String, Long>();
        lineItems.forEach((id, c) -> out.put(id, c.periodBudgetMicros()));
        return out;
    }

    /** Timestamp of the newest decision read: the log is complete up to about here. */
    public long latestDecisionMs() {
        return latestDecisionMs.get();
    }

    /** Forgets everything, before the topics are read again from the beginning. */
    public void reset() {
        minutes.clear();
        servedByPeriod.clear();
        lineItems.clear();
        latestDecisionMs.set(Long.MIN_VALUE);
    }

    public void prune(long now) {
        minutes.values().removeIf(m -> m.windowStartMs() < now - KEEP_MINUTES_MS);
        long current = period.index(now);
        servedByPeriod.keySet().removeIf(p -> p < current - KEEP_PERIODS);
    }
}
