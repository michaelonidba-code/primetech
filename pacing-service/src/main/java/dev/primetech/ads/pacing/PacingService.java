package dev.primetech.ads.pacing;

import dev.primetech.ads.common.LineItemMinute;
import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.pacing.PacingPeriod;
import dev.primetech.ads.common.pacing.PacingState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Holds one pacer per line item, feeds them delivery data, and runs the control tick. Inputs arrive
 * on the Kafka thread ({@link #lineItem}, {@link #minute}); {@link #tick} runs on a timer.
 */
public final class PacingService {
    private static final Logger log = LoggerFactory.getLogger(PacingService.class);

    private final PacingPeriod period;
    private final PacingStore store;
    private final LongSupplier clock;
    private final Map<String, LineItemPacer> pacers = new ConcurrentHashMap<>();
    /** Latest revision seen per line item minute, so older revisions arriving late are ignored. */
    private final Map<String, Integer> revisions = new ConcurrentHashMap<>();
    /**
     * Delivery per line item and minute, kept here as well as in the pacers: the two input topics
     * are read together, so a line item's minutes can arrive before its config, and a changed
     * config replaces the pacer. Either way the new pacer is replayed from this.
     */
    private final Map<String, Map<Long, Long>> delivered = new ConcurrentHashMap<>();
    /**
     * How far delivery is known: the end of the latest minute reported by any line item. The stream
     * processor's watermark is global, so all line items' minutes up to here have fired.
     */
    private final AtomicLong horizonMs = new AtomicLong(Long.MIN_VALUE);
    private final Counter storeErrors;
    private volatile List<PacingState> latest = List.of();
    /** When pacing state last reached the store; the ad server falls back if this gets old. */
    private final AtomicLong lastPublishMs;
    /** Overspend threshold for the overspent-line-items gauge: 1% over budget. */
    static final double OVERSPEND_TOLERANCE = 1.01;

    public PacingService(PacingPeriod period, PacingStore store, LongSupplier clock, MeterRegistry registry) {
        this.period = period;
        this.store = store;
        this.clock = clock;
        this.storeErrors = registry.counter("pacing.store.errors");
        this.lastPublishMs = new AtomicLong(clock.getAsLong());
        registry.gauge("pacing.line.items", pacers, Map::size);
        Gauge.builder("pacing.publish.age.seconds", () -> (clock.getAsLong() - lastPublishMs.get()) / 1000.0)
                .description("Seconds since pacing state was last written to Redis").register(registry);
        Gauge.builder("pacing.delivery.lag.seconds", () -> horizonMs.get() == Long.MIN_VALUE
                        ? Double.NaN : (clock.getAsLong() - horizonMs.get()) / 1000.0)
                .description("How far behind real time delivered spend from the stream processor is").register(registry);
        Gauge.builder("pacing.period.elapsed.ratio", () -> period.elapsed(clock.getAsLong())).register(registry);
        for (PacingState.Status status : PacingState.Status.values()) {
            Gauge.builder("pacing.line.items.by.status", () -> latest.stream().filter(s -> s.status() == status).count())
                    .tag("status", status.name()).register(registry);
        }
        Gauge.builder("pacing.delivered.budget.ratio.max", () -> latest.stream()
                        .filter(s -> s.budgetMicros() > 0)
                        .mapToDouble(s -> (double) s.deliveredMicros() / s.budgetMicros()).max().orElse(0))
                .description("Highest delivered / budget among line items this period").register(registry);
        Gauge.builder("pacing.overspent.line.items", () -> latest.stream()
                        .filter(s -> s.deliveredMicros() > s.budgetMicros() * OVERSPEND_TOLERANCE).count())
                .description("Line items more than 1% over budget this period").register(registry);
    }

    public void lineItem(LineItemConfig config) {
        pacers.compute(config.lineItemId(), (id, old) -> {
            if (old != null && old.config().equals(config)) {
                return old;
            }
            var pacer = new LineItemPacer(config, period);
            delivered.getOrDefault(id, Map.of()).forEach(pacer::delivered);
            return pacer;
        });
    }

    public void minute(LineItemMinute m) {
        String key = m.key();
        Integer seen = revisions.get(key);
        if (seen != null && seen >= m.revision()) {
            return;
        }
        revisions.put(key, m.revision());
        horizonMs.accumulateAndGet(m.windowEndMs(), Math::max);
        delivered.computeIfAbsent(m.lineItemId(), k -> new ConcurrentHashMap<>()).put(m.windowStartMs(), m.spendMicros());
        LineItemPacer pacer = pacers.get(m.lineItemId());
        if (pacer != null) {
            pacer.delivered(m.windowStartMs(), m.spendMicros());
        }
    }

    /** One control step for every line item. Store failures are logged and retried next tick. */
    public List<PacingState> tick() {
        long now = clock.getAsLong();
        long periodIndex = period.index(now);
        long horizon = horizonMs.get() == Long.MIN_VALUE ? period.start(periodIndex) : horizonMs.get();
        Map<String, Long> served;
        try {
            served = store.served(periodIndex, pacers.keySet());
        } catch (Exception e) {
            storeErrors.increment();
            log.warn("cannot read served spend, skipping tick: {}", e.toString());
            return latest;
        }
        var states = new ArrayList<PacingState>(pacers.size());
        for (LineItemPacer pacer : pacers.values()) {
            states.add(pacer.tick(now, horizon, served.getOrDefault(pacer.config().lineItemId(), 0L)));
        }
        states.sort(Comparator.comparing(PacingState::lineItemId));
        try {
            store.publish(states);
            lastPublishMs.set(now);
        } catch (Exception e) {
            storeErrors.increment();
            log.warn("cannot publish pacing state: {}", e.toString());
        }
        pruneRevisions(period.start(periodIndex));
        latest = List.copyOf(states);
        return latest;
    }

    public List<PacingState> latest() {
        return latest;
    }

    public long horizonMs() {
        return horizonMs.get();
    }

    public LineItemConfig config(String lineItemId) {
        LineItemPacer p = pacers.get(lineItemId);
        return p == null ? null : p.config();
    }

    /** Bookkeeping is only needed for the current period. */
    private void pruneRevisions(long periodStart) {
        revisions.keySet().removeIf(k -> Long.parseLong(k.substring(k.lastIndexOf('|') + 1)) < periodStart);
        delivered.values().forEach(byMinute -> byMinute.keySet().removeIf(m -> m < periodStart));
    }
}
