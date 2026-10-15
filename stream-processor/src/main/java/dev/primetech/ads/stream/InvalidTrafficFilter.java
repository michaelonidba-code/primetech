package dev.primetech.ads.stream;

import dev.primetech.ads.common.EnrichedAdEvent;
import dev.primetech.ads.common.EventType;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;

/**
 * General invalid traffic (GIVT) rule: a device that fires more impressions in one event-time
 * minute than a person could watch is a bot or a broken integration. Once a device crosses the
 * threshold in a minute, its later events in that minute are flagged (kept, but not billable).
 *
 * <p>The first {@code maxImpressionsPerMinute} impressions of an offending minute stay unflagged:
 * the rule only knows a device is abusive once it has seen enough. "First" means first to arrive
 * here; upstream re-keying does not preserve a device's order, so which events get flagged can
 * vary between runs, but how many does not. Devices without consent have
 * no stable id, so this rule cannot see them; that gap is inherent to privacy-first tracking.
 */
public final class InvalidTrafficFilter extends KeyedProcessFunction<String, EnrichedAdEvent, EnrichedAdEvent> {
    public static final String DEVICE_IMPRESSION_RATE = "DEVICE_IMPRESSION_RATE";
    private static final long MINUTE_MS = 60_000;

    private final int maxImpressionsPerMinute;
    private final long retentionMs;
    private transient MapState<Long, Integer> impressionsByMinute;
    private transient Counter flagged;

    public InvalidTrafficFilter(int maxImpressionsPerMinute, Duration allowedLateness) {
        this.maxImpressionsPerMinute = maxImpressionsPerMinute;
        this.retentionMs = allowedLateness.toMillis();
    }

    @Override
    public void open(OpenContext openContext) {
        impressionsByMinute = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("impressions-by-minute", Long.class, Integer.class));
        flagged = getRuntimeContext().getMetricGroup().counter("ivtFlagged");
    }

    @Override
    public void processElement(EnrichedAdEvent event, Context ctx, Collector<EnrichedAdEvent> out) throws Exception {
        long minute = Math.floorDiv(event.eventTimeMs(), MINUTE_MS);
        Integer count = impressionsByMinute.get(minute);
        int impressions = count == null ? 0 : count;
        if (event.type() == EventType.IMPRESSION) {
            impressions++;
            impressionsByMinute.put(minute, impressions);
            if (count == null) {
                // Forget the minute once no late event can still land in it.
                ctx.timerService().registerEventTimeTimer((minute + 1) * MINUTE_MS + retentionMs);
            }
        }
        if (impressions > maxImpressionsPerMinute) {
            flagged.inc();
            out.collect(event.withIvt(DEVICE_IMPRESSION_RATE));
        } else {
            out.collect(event);
        }
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<EnrichedAdEvent> out) throws Exception {
        impressionsByMinute.remove((timestamp - retentionMs) / MINUTE_MS - 1);
    }
}
