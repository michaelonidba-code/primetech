package dev.primetech.ads.stream;

import dev.primetech.ads.common.AdEvent;
import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.EnrichedAdEvent;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ReadOnlyBroadcastState;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Joins campaign metadata onto events. Metadata is small and changes rarely, so it is broadcast to
 * every parallel instance instead of being looked up per event.
 *
 * <p>Events for a campaign whose metadata has not arrived yet (typical just after start-up) are
 * buffered per campaign and released as soon as it arrives. If it never does, they are released
 * unenriched after {@code maxWait} and counted, rather than held forever.
 *
 * <p>Records released from the broadcast side or a timer lose their event timestamp; the pipeline
 * restores it from the payload before windowing ({@link RestoreEventTime}).
 */
public final class EnrichWithCampaign
        extends KeyedBroadcastProcessFunction<String, AdEvent, CampaignMetadata, EnrichedAdEvent> {

    public static final MapStateDescriptor<String, CampaignMetadata> METADATA =
            new MapStateDescriptor<>("campaign-metadata", String.class, CampaignMetadata.class);
    static final ListStateDescriptor<AdEvent> BUFFER = new ListStateDescriptor<>("awaiting-metadata", AdEvent.class);

    private final Duration maxWait;
    private transient ListState<AdEvent> buffer;
    private transient Counter released;
    private transient Counter missing;

    public EnrichWithCampaign(Duration maxWait) {
        this.maxWait = maxWait;
    }

    @Override
    public void open(OpenContext openContext) {
        buffer = getRuntimeContext().getListState(BUFFER);
        released = getRuntimeContext().getMetricGroup().counter("bufferedEventsReleased");
        missing = getRuntimeContext().getMetricGroup().counter("metadataMissing");
    }

    @Override
    public void processElement(AdEvent event, ReadOnlyContext ctx, Collector<EnrichedAdEvent> out) throws Exception {
        ReadOnlyBroadcastState<String, CampaignMetadata> metadata = ctx.getBroadcastState(METADATA);
        CampaignMetadata meta = metadata.get(event.campaignId());
        if (meta != null) {
            out.collect(EnrichedAdEvent.of(event, meta, ctx.currentProcessingTime()));
            return;
        }
        if (!buffer.get().iterator().hasNext()) {
            ctx.timerService().registerProcessingTimeTimer(ctx.currentProcessingTime() + maxWait.toMillis());
        }
        buffer.add(event);
    }

    @Override
    public void processBroadcastElement(CampaignMetadata meta, Context ctx, Collector<EnrichedAdEvent> out)
            throws Exception {
        ctx.getBroadcastState(METADATA).put(meta.campaignId(), meta);
        long now = ctx.currentProcessingTime();
        ctx.applyToKeyedState(BUFFER, (campaignId, state) -> {
            if (!campaignId.equals(meta.campaignId())) {
                return;
            }
            for (AdEvent e : state.get()) {
                out.collect(EnrichedAdEvent.of(e, meta, now));
                released.inc();
            }
            state.clear();
        });
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<EnrichedAdEvent> out) throws Exception {
        List<AdEvent> waiting = new ArrayList<>();
        buffer.get().forEach(waiting::add);
        if (waiting.isEmpty()) {
            return;
        }
        CampaignMetadata meta = ctx.getBroadcastState(METADATA).get(ctx.getCurrentKey());
        for (AdEvent e : waiting) {
            out.collect(EnrichedAdEvent.of(e, meta, ctx.currentProcessingTime()));
            if (meta == null) {
                missing.inc();
            } else {
                released.inc();
            }
        }
        buffer.clear();
    }
}
