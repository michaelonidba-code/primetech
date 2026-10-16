package dev.primetech.ads.audit;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeSet;

/**
 * The reconciliations, as pure functions over data already read from each source. Each compares
 * two records of the same fact that are produced independently, so a disagreement means one of
 * them is wrong: lost or double-counted events, a broken counter, or spend beyond a budget.
 */
public final class Checks {
    public static final String FLINK_VS_DRUID = "flink-vs-druid";
    public static final String SERVED_VS_DECISIONS = "served-vs-decision-log";
    public static final String BUDGETS = "budget-compliance";
    public static final String DELIVERY_RATIO = "delivered-vs-served";

    /** Overspend allowed before a line item counts as over budget. */
    static final double BUDGET_TOLERANCE = 1.01;
    /** Expected range of delivered / served spend: some served ads are never played, none are invented. */
    static final double MIN_DELIVERY_RATIO = 0.85;
    static final double MAX_DELIVERY_RATIO = 1.02;

    /** Billable impressions and spend for one line item in one minute. */
    public record Totals(long impressions, long spendMicros) {
    }

    /**
     * Flink's exactly-once minute totals against Druid's rollup of the clean event stream, for
     * minutes that can no longer change. Both derive from the same events through different code
     * (Flink windows vs Druid ingestion and SQL), so they must agree exactly.
     *
     * @param flink keyed by {@code lineItemId|minuteStartMs}
     * @param druid keyed the same way
     */
    public static CheckResult flinkVsDruid(Map<String, Totals> flink, Map<String, Totals> druid, long from, long to, long now) {
        var keys = new TreeSet<>(flink.keySet());
        keys.addAll(druid.keySet());
        var problems = new ArrayList<String>();
        long spend = 0;
        for (String key : keys) {
            Totals f = flink.getOrDefault(key, new Totals(0, 0));
            Totals d = druid.getOrDefault(key, new Totals(0, 0));
            spend += f.spendMicros();
            if (!f.equals(d)) {
                problems.add(String.format("%s: Flink %d imps / %d µ$, Druid %d imps / %d µ$",
                        key, f.impressions(), f.spendMicros(), d.impressions(), d.spendMicros()));
            }
        }
        String summary = problems.isEmpty()
                ? String.format("%d line item minutes agree exactly (%s of spend)", keys.size(), dollars(spend))
                : String.format("%d of %d line item minutes disagree", problems.size(), keys.size());
        return CheckResult.of(FLINK_VS_DRUID, from, to, keys.size(), problems, summary, now);
    }

    /**
     * The ad server's served-spend counters in Redis (what pacing steers by) against the sum of
     * direct wins in its decision log. Both are written by the ad server for every direct win, one
     * to Redis and one to Kafka, so they must agree exactly for a settled period.
     */
    public static CheckResult servedVsDecisions(Map<String, Long> decisionLog, Map<String, Long> redis,
                                                long from, long to, long now) {
        var ids = new TreeSet<>(decisionLog.keySet());
        ids.addAll(redis.keySet());
        var problems = new ArrayList<String>();
        long total = 0;
        for (String id : ids) {
            long logged = decisionLog.getOrDefault(id, 0L);
            long counted = redis.getOrDefault(id, 0L);
            total += logged;
            if (logged != counted) {
                problems.add(String.format("%s: decision log %s, Redis %s", id, dollars(logged), dollars(counted)));
            }
        }
        String summary = problems.isEmpty()
                ? String.format("%d line items agree exactly (%s served)", ids.size(), dollars(total))
                : String.format("%d of %d line items disagree", problems.size(), ids.size());
        return CheckResult.of(SERVED_VS_DECISIONS, from, to, ids.size(), problems, summary, now);
    }

    /** Delivered spend per line item (from Druid) against its budget for a settled period. */
    public static CheckResult budgets(Map<String, Long> budgets, Map<String, Long> delivered, long from, long to, long now) {
        var problems = new ArrayList<String>();
        double worst = 0;
        for (var e : new java.util.TreeMap<>(budgets).entrySet()) {
            long budget = e.getValue();
            long spent = delivered.getOrDefault(e.getKey(), 0L);
            if (budget <= 0) {
                continue;
            }
            worst = Math.max(worst, (double) spent / budget);
            if (spent > budget * BUDGET_TOLERANCE) {
                problems.add(String.format("%s: delivered %s of %s (%.1f%%)", e.getKey(), dollars(spent), dollars(budget),
                        100.0 * spent / budget));
            }
        }
        String summary = problems.isEmpty()
                ? String.format("%d line items within budget, highest at %.1f%%", budgets.size(), 100 * worst)
                : String.format("%d of %d line items over budget by more than 1%%", problems.size(), budgets.size());
        return CheckResult.of(BUDGETS, from, to, budgets.size(), problems, summary, now);
    }

    /**
     * Total delivered (played, valid) against served direct spend for a settled period. Below the
     * range, tracking events are going missing; above it, something counts spend that was never served.
     */
    public static CheckResult deliveryRatio(long deliveredMicros, long servedMicros, long from, long to, long now) {
        if (servedMicros == 0) {
            return CheckResult.pending(DELIVERY_RATIO, "nothing served in the period", now);
        }
        double ratio = (double) deliveredMicros / servedMicros;
        var problems = new ArrayList<String>();
        if (ratio < MIN_DELIVERY_RATIO || ratio > MAX_DELIVERY_RATIO) {
            problems.add(String.format("delivered/served %.3f outside [%.2f, %.2f]", ratio, MIN_DELIVERY_RATIO, MAX_DELIVERY_RATIO));
        }
        String summary = String.format("delivered %s of %s served (%.1f%%)", dollars(deliveredMicros), dollars(servedMicros),
                100 * ratio);
        return CheckResult.of(DELIVERY_RATIO, from, to, 1, problems, summary, now);
    }

    static String dollars(long micros) {
        return String.format("$%,.2f", micros / 1e6);
    }

    private Checks() {
    }
}
