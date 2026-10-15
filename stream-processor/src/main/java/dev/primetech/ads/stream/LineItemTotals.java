package dev.primetech.ads.stream;

import dev.primetech.ads.common.EnrichedAdEvent;
import dev.primetech.ads.common.LineItemMinute;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/**
 * Incremental per-window totals, so a window holds one small accumulator instead of every event.
 * Valid traffic feeds the delivery counts and spend; IVT is only counted.
 */
public final class LineItemTotals implements AggregateFunction<EnrichedAdEvent, LineItemTotals.Acc, LineItemTotals.Acc> {

    /** Flink POJO: public no-arg constructor and public fields. */
    public static final class Acc {
        public String lineItemId;
        public String campaignId;
        public String advertiserId;
        public long impressions;
        public long firstQuartiles;
        public long midpoints;
        public long thirdQuartiles;
        public long completes;
        public long clicks;
        public long spendMicros;
        public long ivtEvents;

        public Acc() {
        }
    }

    @Override
    public Acc createAccumulator() {
        return new Acc();
    }

    @Override
    public Acc add(EnrichedAdEvent e, Acc acc) {
        acc.lineItemId = e.lineItemId();
        acc.campaignId = e.campaignId();
        if (e.advertiserId() != null) {
            acc.advertiserId = e.advertiserId();
        }
        if (e.isIvt()) {
            acc.ivtEvents++;
            return acc;
        }
        switch (e.type()) {
            case IMPRESSION -> acc.impressions++;
            case FIRST_QUARTILE -> acc.firstQuartiles++;
            case MIDPOINT -> acc.midpoints++;
            case THIRD_QUARTILE -> acc.thirdQuartiles++;
            case COMPLETE -> acc.completes++;
            case CLICK -> acc.clicks++;
        }
        acc.spendMicros += e.billableMicros();
        return acc;
    }

    @Override
    public Acc getResult(Acc acc) {
        return acc;
    }

    @Override
    public Acc merge(Acc a, Acc b) {
        a.lineItemId = a.lineItemId != null ? a.lineItemId : b.lineItemId;
        a.campaignId = a.campaignId != null ? a.campaignId : b.campaignId;
        a.advertiserId = a.advertiserId != null ? a.advertiserId : b.advertiserId;
        a.impressions += b.impressions;
        a.firstQuartiles += b.firstQuartiles;
        a.midpoints += b.midpoints;
        a.thirdQuartiles += b.thirdQuartiles;
        a.completes += b.completes;
        a.clicks += b.clicks;
        a.spendMicros += b.spendMicros;
        a.ivtEvents += b.ivtEvents;
        return a;
    }

    /**
     * Stamps the window bounds and a revision. The first firing (at the watermark) is revision 1;
     * each late event inside the allowed lateness fires the window again with the corrected totals.
     */
    public static final class Emit extends ProcessWindowFunction<Acc, LineItemMinute, String, TimeWindow> {
        private static final ValueStateDescriptor<Integer> REVISION = new ValueStateDescriptor<>("revision", Integer.class);

        @Override
        public void process(String key, Context ctx, Iterable<Acc> totals, Collector<LineItemMinute> out)
                throws Exception {
            ValueState<Integer> revisionState = ctx.windowState().getState(REVISION);
            int revision = revisionState.value() == null ? 1 : revisionState.value() + 1;
            revisionState.update(revision);

            Acc t = totals.iterator().next();
            out.collect(new LineItemMinute(key, t.campaignId, t.advertiserId, ctx.window().getStart(),
                    ctx.window().getEnd(), revision, t.impressions, t.firstQuartiles, t.midpoints, t.thirdQuartiles,
                    t.completes, t.clicks, t.spendMicros, t.ivtEvents, ctx.currentProcessingTime()));
        }

        @Override
        public void clear(Context ctx) {
            ctx.windowState().getState(REVISION).clear();
        }
    }
}
