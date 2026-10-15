package dev.primetech.ads.audit;

import dev.primetech.ads.common.pacing.PacingPeriod;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Runs every reconciliation over data that has settled, and exposes the results as metrics (for
 * alerts), as recent history (for the dashboard) and as records (for the audit trail).
 *
 * <p>Settled means no source can still change it: minutes older than the stream processor's
 * allowed lateness (10 min) plus a margin, and pacing periods that ended a few minutes ago.
 */
public final class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final long MINUTE_MS = 60_000;
    /** Minutes this old can no longer be revised: 10 min allowed lateness plus 2 min margin. */
    static final long MINUTE_SETTLE_MS = 12 * MINUTE_MS;
    /** How many settled minutes each run compares. */
    static final long MINUTE_WINDOW_MS = 30 * MINUTE_MS;
    /** A period is checked once it ended this long ago: its last minutes are closed and ingested. */
    static final long PERIOD_SETTLE_MS = 3 * MINUTE_MS;
    /** The decision log must have been read at least this far past the period's end. */
    static final long DECISION_MARGIN_MS = 30_000;
    static final int HISTORY = 30;
    static final List<String> CHECKS = List.of(Checks.FLINK_VS_DRUID, Checks.SERVED_VS_DECISIONS, Checks.BUDGETS,
            Checks.DELIVERY_RATIO);

    private final AuditLedger ledger;
    private final Sources sources;
    private final PacingPeriod period;
    private final LongSupplier clock;
    private final BooleanSupplier caughtUp;
    private final Consumer<CheckResult> publisher;
    private final Map<String, Deque<CheckResult>> history = new ConcurrentHashMap<>();
    /** The time the audit started: windows never reach back before data it could have read. */
    private final long startedMs;

    public AuditService(AuditLedger ledger, Sources sources, PacingPeriod period, LongSupplier clock,
                        BooleanSupplier caughtUp, Consumer<CheckResult> publisher, MeterRegistry registry) {
        this.ledger = ledger;
        this.sources = sources;
        this.period = period;
        this.clock = clock;
        this.caughtUp = caughtUp;
        this.publisher = publisher;
        this.startedMs = clock.getAsLong();
        for (String check : CHECKS) {
            history.put(check, new ArrayDeque<>());
            Gauge.builder("audit.check.ok", () -> okValue(latest(check)))
                    .description("1 if the latest completed run agreed, 0 if it found a mismatch, NaN while pending")
                    .tag("check", check).register(registry);
            Gauge.builder("audit.check.mismatches", () -> latest(check) == null ? Double.NaN : latest(check).mismatches())
                    .tag("check", check).register(registry);
            Gauge.builder("audit.check.compared", () -> latest(check) == null ? Double.NaN : latest(check).compared())
                    .tag("check", check).register(registry);
            Gauge.builder("audit.check.last.completed.seconds", () -> lastCompletedMs(check) / 1000.0)
                    .description("When the check last completed (OK or MISMATCH), as a Unix timestamp")
                    .tag("check", check).register(registry);
        }
    }

    /** One audit round. Each check is independent: one source being down does not stop the others. */
    public List<CheckResult> run() {
        long now = clock.getAsLong();
        ledger.prune(now);
        var results = new ArrayList<CheckResult>();
        if (!caughtUp.getAsBoolean()) {
            for (String check : CHECKS) {
                results.add(CheckResult.pending(check, "reading the topics from the beginning", now));
            }
        } else {
            results.add(flinkVsDruid(now));
            results.addAll(periodChecks(now));
        }
        for (CheckResult r : results) {
            record(r);
        }
        return results;
    }

    private CheckResult flinkVsDruid(long now) {
        long to = Math.floorDiv(now - MINUTE_SETTLE_MS, MINUTE_MS) * MINUTE_MS;
        long from = to - MINUTE_WINDOW_MS; // well inside the ledger's 3 h of minutes
        try {
            return Checks.flinkVsDruid(ledger.minutes(from, to), sources.druidMinutes(from, to), from, to, now);
        } catch (Exception e) {
            return CheckResult.error(Checks.FLINK_VS_DRUID, "Druid unavailable: " + e.getMessage(), now);
        }
    }

    private List<CheckResult> periodChecks(long now) {
        long p = period.index(now - PERIOD_SETTLE_MS) - 1;
        long from = period.start(p);
        long to = period.end(p);
        Map<String, Long> served = ledger.served(p);
        if (ledger.latestDecisionMs() < to + DECISION_MARGIN_MS || served.isEmpty()) {
            String why = served.isEmpty() ? "no direct spend served in the last settled period" : "decision log not read up to the period end yet";
            return List.of(CheckResult.pending(Checks.SERVED_VS_DECISIONS, why, now),
                    CheckResult.pending(Checks.BUDGETS, why, now), CheckResult.pending(Checks.DELIVERY_RATIO, why, now));
        }
        var results = new ArrayList<CheckResult>();
        try {
            var ids = new HashSet<>(served.keySet());
            ids.addAll(ledger.budgets().keySet());
            results.add(Checks.servedVsDecisions(served, sources.redisServed(p, ids), from, to, now));
        } catch (Exception e) {
            results.add(CheckResult.error(Checks.SERVED_VS_DECISIONS, "Redis unavailable: " + e.getMessage(), now));
        }
        try {
            Map<String, Long> budgets = ledger.budgets();
            Map<String, Long> delivered = sources.druidSpend(from, to);
            results.add(Checks.budgets(budgets, delivered, from, to, now));
            long deliveredDirect = budgets.keySet().stream().mapToLong(id -> delivered.getOrDefault(id, 0L)).sum();
            long servedDirect = served.values().stream().mapToLong(Long::longValue).sum();
            results.add(Checks.deliveryRatio(deliveredDirect, servedDirect, from, to, now));
        } catch (Exception e) {
            results.add(CheckResult.error(Checks.BUDGETS, "Druid unavailable: " + e.getMessage(), now));
            results.add(CheckResult.error(Checks.DELIVERY_RATIO, "Druid unavailable: " + e.getMessage(), now));
        }
        return results;
    }

    private void record(CheckResult r) {
        Deque<CheckResult> h = history.get(r.check());
        synchronized (h) {
            h.addFirst(r);
            while (h.size() > HISTORY) {
                h.removeLast();
            }
        }
        if (r.status() == CheckResult.Status.MISMATCH) {
            log.warn("{}: {} {}", r.check(), r.summary(), r.examples());
        } else if (r.status() == CheckResult.Status.ERROR) {
            log.warn("{}: {}", r.check(), r.summary());
        }
        if (r.status() == CheckResult.Status.OK || r.status() == CheckResult.Status.MISMATCH) {
            try {
                publisher.accept(r);
            } catch (RuntimeException e) {
                log.warn("cannot publish audit result: {}", e.toString());
            }
        }
    }

    /** The latest OK or MISMATCH result: pending and error runs say nothing about the data. */
    CheckResult latest(String check) {
        Deque<CheckResult> h = history.get(check);
        synchronized (h) {
            return h.stream().filter(r -> r.status() == CheckResult.Status.OK || r.status() == CheckResult.Status.MISMATCH)
                    .findFirst().orElse(null);
        }
    }

    private long lastCompletedMs(String check) {
        CheckResult r = latest(check);
        return r == null ? startedMs : r.ranAtMs();
    }

    private static double okValue(CheckResult r) {
        return r == null ? Double.NaN : r.status() == CheckResult.Status.OK ? 1 : 0;
    }

    /** For the stats endpoint: per check, the latest run of any status and the latest completed one. */
    public Map<String, Object> stats() {
        var out = new LinkedHashMap<String, Object>();
        for (String check : CHECKS) {
            Deque<CheckResult> h = history.get(check);
            List<CheckResult> runs;
            synchronized (h) {
                runs = List.copyOf(h);
            }
            var m = new LinkedHashMap<String, Object>();
            m.put("latest", runs.isEmpty() ? null : runs.get(0));
            m.put("latestCompleted", latest(check));
            m.put("recent", runs.stream().map(r -> Map.of("status", r.status(), "ranAtMs", r.ranAtMs(),
                    "mismatches", r.mismatches())).toList());
            out.put(check, m);
        }
        return out;
    }
}
