package dev.primetech.ads.stream;

import dev.primetech.ads.common.AdEvent;
import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.EnrichedAdEvent;
import dev.primetech.ads.common.LineItemMinute;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.util.OutputTag;

import java.time.Duration;

/**
 * The processing topology, independent of Kafka so tests can drive it with in-memory sources.
 *
 * <pre>
 * events ─► dedup (by eventId) ─► enrich (by campaignId, + broadcast metadata) ─► IVT (by device) ─┬─► clean events
 *                                                                                                  └─► 1-min windows (by line item) ─► line-item minutes
 * </pre>
 *
 * The input stream must already carry event-time timestamps and watermarks.
 */
public final class AdPipeline {
    /** Events too late even for the allowed lateness. They are still in the clean output; only the real-time totals miss them. */
    public static final OutputTag<EnrichedAdEvent> TOO_LATE =
            new OutputTag<>("too-late-for-minute-totals", TypeInformation.of(EnrichedAdEvent.class));

    public record Outputs(DataStream<EnrichedAdEvent> cleanEvents,
                          DataStream<LineItemMinute> lineItemMinutes,
                          DataStream<EnrichedAdEvent> tooLate) {
    }

    private AdPipeline() {
    }

    public static Outputs build(DataStream<AdEvent> events, DataStream<CampaignMetadata> metadata, PipelineConfig config) {
        DataStream<AdEvent> unique = events
                .keyBy(AdEvent::eventId)
                .process(new Deduplicate(config.dedupTtl()))
                .name("dedup-by-event-id").uid("dedup");

        DataStream<EnrichedAdEvent> enriched = unique
                .keyBy(AdEvent::campaignId)
                .connect(metadata.broadcast(EnrichWithCampaign.METADATA))
                .process(new EnrichWithCampaign(config.metadataWait()))
                .name("enrich-with-campaign").uid("enrich");

        // Keying by a null id would funnel every non-consented event to one subtask, so only
        // events with a device id go through the per-device rule.
        DataStream<EnrichedAdEvent> withDevice = enriched.filter(e -> e.deviceIdHash() != null).name("has-device-id");
        DataStream<EnrichedAdEvent> withoutDevice = enriched.filter(e -> e.deviceIdHash() == null).name("no-device-id");
        DataStream<EnrichedAdEvent> clean = withDevice
                .keyBy(EnrichedAdEvent::deviceIdHash)
                .process(new InvalidTrafficFilter(config.ivtMaxImpressionsPerDeviceMinute(), config.allowedLateness()))
                .name("flag-invalid-traffic").uid("ivt")
                .union(withoutDevice)
                .transform("restore-event-time", TypeInformation.of(EnrichedAdEvent.class), new RestoreEventTime())
                .uid("restore-event-time");

        SingleOutputStreamOperator<LineItemMinute> minutes = minuteTotals(clean, config);
        return new Outputs(clean, minutes, minutes.getSideOutput(TOO_LATE));
    }

    /**
     * One-minute event-time totals per line item. A window first fires when the watermark passes
     * its end, then again (a new revision) for late events within the allowed lateness, at most
     * once per late-firing delay.
     */
    static SingleOutputStreamOperator<LineItemMinute> minuteTotals(DataStream<EnrichedAdEvent> events,
                                                                   PipelineConfig config) {
        return events
                .keyBy(EnrichedAdEvent::lineItemId)
                .window(TumblingEventTimeWindows.of(Duration.ofMinutes(1)))
                .trigger(new CoalescingLateTrigger(config.lateFiringDelay()))
                .allowedLateness(config.allowedLateness())
                .sideOutputLateData(TOO_LATE)
                .aggregate(new LineItemTotals(), new LineItemTotals.Emit())
                .name("line-item-minute-totals").uid("line-item-minutes");
    }
}
