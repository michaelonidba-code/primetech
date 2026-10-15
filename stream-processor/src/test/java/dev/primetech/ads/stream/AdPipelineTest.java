package dev.primetech.ads.stream;

import dev.primetech.ads.common.AdEvent;
import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.EnrichedAdEvent;
import dev.primetech.ads.common.EventType;
import dev.primetech.ads.common.LineItemMinute;
import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.common.eventtime.WatermarkGenerator;
import org.apache.flink.api.common.eventtime.WatermarkOutput;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.primetech.ads.stream.Events.CAMP_1;
import static dev.primetech.ads.stream.Events.CAMP_2;
import static dev.primetech.ads.stream.Events.T0;
import static dev.primetech.ads.stream.Events.event;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs the whole topology on a local Flink cluster with in-memory sources. */
class AdPipelineTest {

    @RegisterExtension
    static final MiniClusterExtension FLINK = new MiniClusterExtension(
            new MiniClusterResourceConfiguration.Builder().setNumberTaskManagers(1).setNumberSlotsPerTaskManager(4).build());

    /** Emits a watermark after every event, so lateness is deterministic regardless of timing. */
    static final class PerEventWatermarks implements WatermarkGenerator<AdEvent> {
        private long max = Long.MIN_VALUE;

        @Override
        public void onEvent(AdEvent event, long timestamp, WatermarkOutput output) {
            max = Math.max(max, timestamp);
            output.emitWatermark(new Watermark(max - 5_000));
        }

        @Override
        public void onPeriodicEmit(WatermarkOutput output) {
        }
    }

    private record Result(List<EnrichedAdEvent> clean, List<LineItemMinute> minutes) {
    }

    private static Result run(List<AdEvent> input, List<CampaignMetadata> metadata) throws Exception {
        var env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        DataStream<AdEvent> events = env.fromData(input, TypeInformation.of(AdEvent.class))
                .setParallelism(1)
                .assignTimestampsAndWatermarks(WatermarkStrategy.<AdEvent>forGenerator(ctx -> new PerEventWatermarks())
                        .withTimestampAssigner((e, ts) -> e.eventTimeMs()))
                .setParallelism(1);
        DataStream<CampaignMetadata> meta = env.fromData(metadata, TypeInformation.of(CampaignMetadata.class));

        AdPipeline.Outputs out = AdPipeline.build(events, meta, PipelineConfig.forTests());
        CloseableIterator<EnrichedAdEvent> clean = out.cleanEvents().collectAsync();
        CloseableIterator<LineItemMinute> minutes = out.lineItemMinutes().collectAsync();
        env.execute("pipeline-test");

        var cleanList = new ArrayList<EnrichedAdEvent>();
        clean.forEachRemaining(cleanList::add);
        var minuteList = new ArrayList<LineItemMinute>();
        minutes.forEachRemaining(minuteList::add);
        return new Result(cleanList, minuteList);
    }

    /** Latest revision of each line item minute, as a reader of the upsert topic would see it. */
    private static Map<String, LineItemMinute> latest(List<LineItemMinute> minutes) {
        return minutes.stream().collect(Collectors.toMap(LineItemMinute::key, Function.identity(),
                (a, b) -> a.revision() >= b.revision() ? a : b));
    }

    @Test
    void endToEnd() throws Exception {
        var input = new ArrayList<AdEvent>();
        // Two people watching camp-1 in minute 0: impression and full quartiles.
        for (String viewer : List.of("v1", "v2")) {
            input.add(event(viewer + "-imp", EventType.IMPRESSION, T0 + 1_000, "camp-1", viewer));
            input.add(event(viewer + "-q1", EventType.FIRST_QUARTILE, T0 + 8_500, "camp-1", viewer));
            input.add(event(viewer + "-mid", EventType.MIDPOINT, T0 + 16_000, "camp-1", viewer));
            input.add(event(viewer + "-q3", EventType.THIRD_QUARTILE, T0 + 23_500, "camp-1", viewer));
            input.add(event(viewer + "-done", EventType.COMPLETE, T0 + 31_000, "camp-1", viewer));
        }
        // A retried batch: every event of v1 again.
        input.addAll(new ArrayList<>(input.subList(0, 5)));
        // A viewer without consent (no device id) on camp-2, with a click.
        input.add(event("anon-imp", EventType.IMPRESSION, T0 + 2_000, "camp-2", null));
        input.add(event("anon-click", EventType.CLICK, T0 + 3_000, "camp-2", null));
        // A bot on camp-2: 25 impressions within minute 0.
        for (int i = 0; i < 25; i++) {
            input.add(event("bot-" + i, EventType.IMPRESSION, T0 + 32_000 + i * 1_000, "camp-2", "bot-device"));
        }
        // Traffic in minute 1 pushes the watermark past minute 0, so minute 0 fires...
        input.add(event("v3-imp", EventType.IMPRESSION, T0 + 70_000, "camp-1", "v3"));
        // ...then a late impression for minute 0 arrives (buffered on the device) and fires it again.
        input.add(event("late-imp", EventType.IMPRESSION, T0 + 20_000, "camp-1", "v4"));
        // Advance the watermark past minute 1 so it fires too.
        input.add(event("v5-imp", EventType.IMPRESSION, T0 + 130_000, "camp-2", "v5"));

        Result result = run(input, List.of(CAMP_1, CAMP_2));

        // Dedup: each event id exactly once.
        assertThat(result.clean()).extracting(EnrichedAdEvent::eventId).doesNotHaveDuplicates();
        assertThat(result.clean()).hasSize(input.size() - 5);
        // Enrichment.
        assertThat(result.clean()).allSatisfy(e -> assertThat(e.advertiserName()).isNotNull());
        // IVT: the bot's impressions beyond 20 in the minute. Which five depends on arrival order
        // after the shuffles, so only the count and the device are fixed.
        assertThat(result.clean()).filteredOn(EnrichedAdEvent::isIvt).hasSize(5)
                .allSatisfy(e -> assertThat(e.deviceIdHash()).isEqualTo("bot-device"));

        Map<String, LineItemMinute> totals = latest(result.minutes());
        LineItemMinute camp1Minute0 = totals.get("camp-1-li-0|" + T0);
        assertThat(camp1Minute0.impressions()).as("v1, v2 and the late one").isEqualTo(3);
        assertThat(camp1Minute0.completes()).isEqualTo(2);
        assertThat(camp1Minute0.spendMicros()).isEqualTo(3 * 30_000L);
        // How many revisions minute 0 went through depends on how watermarks and records interleave
        // across parallel subtasks; minuteTotalsAreRevisedForLateEvents pins that down.
        assertThat(camp1Minute0.advertiserId()).isEqualTo("adv-1");

        LineItemMinute camp2Minute0 = totals.get("camp-2-li-0|" + T0);
        assertThat(camp2Minute0.impressions()).as("anon + 20 unflagged bot impressions").isEqualTo(21);
        assertThat(camp2Minute0.clicks()).isEqualTo(1);
        assertThat(camp2Minute0.ivtEvents()).isEqualTo(5);
        assertThat(camp2Minute0.spendMicros()).as("IVT is not billed").isEqualTo(21 * 30_000L);

        assertThat(totals.get("camp-1-li-0|" + (T0 + 60_000)).impressions()).isEqualTo(1);
    }

    /** Per-event watermarks, for streams of already enriched events. */
    static final class EnrichedWatermarks implements WatermarkGenerator<EnrichedAdEvent> {
        private long max = Long.MIN_VALUE;

        @Override
        public void onEvent(EnrichedAdEvent event, long timestamp, WatermarkOutput output) {
            max = Math.max(max, timestamp);
            output.emitWatermark(new Watermark(max - 5_000));
        }

        @Override
        public void onPeriodicEmit(WatermarkOutput output) {
        }
    }

    @Test
    void minuteTotalsAreRevisedForLateEventsAndDropTooLateOnes() throws Exception {
        // Parallelism 1: records and watermarks keep their order, so lateness is deterministic.
        var env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        var input = List.of(
                Events.enriched("a", EventType.IMPRESSION, T0 + 1_000, "d1"),
                Events.enriched("b", EventType.IMPRESSION, T0 + 2_000, "d2"),
                Events.enriched("next", EventType.IMPRESSION, T0 + 70_000, "d3"),    // watermark passes minute 0
                Events.enriched("late", EventType.IMPRESSION, T0 + 30_000, "d4"),    // within allowed lateness
                Events.enriched("far", EventType.IMPRESSION, T0 + 15 * 60_000, "d5"), // watermark passes lateness
                Events.enriched("too-late", EventType.IMPRESSION, T0 + 40_000, "d6"));
        var events = env.fromData(input, TypeInformation.of(EnrichedAdEvent.class))
                .assignTimestampsAndWatermarks(WatermarkStrategy.<EnrichedAdEvent>forGenerator(ctx -> new EnrichedWatermarks())
                        .withTimestampAssigner((e, ts) -> e.eventTimeMs()));
        var minutes = AdPipeline.minuteTotals(events, PipelineConfig.forTests());
        CloseableIterator<LineItemMinute> out = minutes.collectAsync();
        CloseableIterator<EnrichedAdEvent> tooLate = minutes.getSideOutput(AdPipeline.TOO_LATE).collectAsync();
        env.execute("lateness-test");

        var emitted = new ArrayList<LineItemMinute>();
        out.forEachRemaining(emitted::add);
        var dropped = new ArrayList<EnrichedAdEvent>();
        tooLate.forEachRemaining(dropped::add);

        var minute0 = emitted.stream().filter(m -> m.windowStartMs() == T0).toList();
        assertThat(minute0).extracting(LineItemMinute::revision).containsExactly(1, 2);
        assertThat(minute0).extracting(LineItemMinute::impressions).containsExactly(2L, 3L);
        assertThat(dropped).extracting(EnrichedAdEvent::eventId).containsExactly("too-late");
    }

    @Test
    void minuteTotalsMatchTheCleanEvents() throws Exception {
        // Many interleaved sessions: the windowed totals must agree with a plain count of the output.
        var input = new ArrayList<AdEvent>();
        var types = EventType.values();
        for (int i = 0; i < 2_000; i++) {
            long t = T0 + (i * 97L) % 240_000;
            input.add(event("e" + i, types[i % types.length], t, i % 3 == 0 ? "camp-2" : "camp-1", "dev-" + (i % 400)));
        }
        input.sort(Comparator.comparingLong(AdEvent::eventTimeMs));
        input.add(event("flush", EventType.IMPRESSION, T0 + 600_000, "camp-1", "dev-x"));

        Result result = run(input, List.of(CAMP_1, CAMP_2));

        Map<String, Long> expectedImpressions = new HashMap<>();
        for (EnrichedAdEvent e : result.clean()) {
            if (e.type() == EventType.IMPRESSION && !e.isIvt()) {
                expectedImpressions.merge(e.lineItemId() + "|" + (e.eventTimeMs() / 60_000 * 60_000), 1L, Long::sum);
            }
        }
        Map<String, Long> actual = latest(result.minutes()).values().stream()
                .filter(m -> m.impressions() > 0)
                .collect(Collectors.toMap(LineItemMinute::key, LineItemMinute::impressions));
        assertThat(actual).isEqualTo(expectedImpressions);
    }
}
