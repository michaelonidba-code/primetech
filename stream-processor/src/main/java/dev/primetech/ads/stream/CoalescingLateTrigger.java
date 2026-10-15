package dev.primetech.ads.stream;

import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.windowing.triggers.Trigger;
import org.apache.flink.streaming.api.windowing.triggers.TriggerResult;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;

import java.time.Duration;

/**
 * Event-time trigger with batched late firings. The window fires once when the watermark passes
 * its end, like the default trigger. Late events then schedule at most one extra firing per
 * {@code lateDelay} instead of one per event, which would otherwise rewrite the same window
 * hundreds of times when a burst of buffered device events arrives.
 *
 * <p>Caveat: a late firing still pending when the window's allowed lateness runs out is dropped
 * with the window. That needs the watermark to advance by the whole allowed lateness within
 * {@code lateDelay}, which only happens when catching up on a backlog.
 */
public final class CoalescingLateTrigger extends Trigger<Object, TimeWindow> {
    private static final ValueStateDescriptor<Long> PENDING = new ValueStateDescriptor<>("late-firing-at", Long.class);

    private final long lateDelayMs;

    public CoalescingLateTrigger(Duration lateDelay) {
        this.lateDelayMs = lateDelay.toMillis();
    }

    @Override
    public TriggerResult onElement(Object element, long timestamp, TimeWindow window, TriggerContext ctx) throws Exception {
        if (window.maxTimestamp() > ctx.getCurrentWatermark()) {
            ctx.registerEventTimeTimer(window.maxTimestamp());
            return TriggerResult.CONTINUE;
        }
        // The window has already fired: this is a late event.
        if (lateDelayMs == 0) {
            return TriggerResult.FIRE;
        }
        ValueState<Long> pending = ctx.getPartitionedState(PENDING);
        if (pending.value() == null) {
            long fireAt = ctx.getCurrentProcessingTime() + lateDelayMs;
            ctx.registerProcessingTimeTimer(fireAt);
            pending.update(fireAt);
        }
        return TriggerResult.CONTINUE;
    }

    @Override
    public TriggerResult onEventTime(long time, TimeWindow window, TriggerContext ctx) {
        return time == window.maxTimestamp() ? TriggerResult.FIRE : TriggerResult.CONTINUE;
    }

    @Override
    public TriggerResult onProcessingTime(long time, TimeWindow window, TriggerContext ctx) throws Exception {
        ctx.getPartitionedState(PENDING).clear();
        return TriggerResult.FIRE;
    }

    @Override
    public void clear(TimeWindow window, TriggerContext ctx) throws Exception {
        ctx.deleteEventTimeTimer(window.maxTimestamp());
        ValueState<Long> pending = ctx.getPartitionedState(PENDING);
        if (pending.value() != null) {
            ctx.deleteProcessingTimeTimer(pending.value());
            pending.clear();
        }
    }

    @Override
    public String toString() {
        return "CoalescingLateTrigger(" + lateDelayMs + "ms)";
    }
}
