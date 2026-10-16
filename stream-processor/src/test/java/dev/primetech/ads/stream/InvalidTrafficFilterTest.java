package dev.primetech.ads.stream;

import dev.primetech.ads.common.EnrichedAdEvent;
import dev.primetech.ads.common.EventType;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.streaming.util.ProcessFunctionTestHarnesses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static dev.primetech.ads.stream.Events.T0;
import static dev.primetech.ads.stream.Events.enriched;
import static org.assertj.core.api.Assertions.assertThat;

class InvalidTrafficFilterTest {
    private static final int MAX = 3;
    private KeyedOneInputStreamOperatorTestHarness<String, EnrichedAdEvent, EnrichedAdEvent> harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = ProcessFunctionTestHarnesses.forKeyedProcessFunction(
                new InvalidTrafficFilter(MAX, Duration.ofMinutes(10)), EnrichedAdEvent::deviceIdHash, Types.STRING);
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    private void send(String id, EventType type, long time, String device) throws Exception {
        harness.processElement(enriched(id, type, time, device), time);
    }

    private List<String> flaggedIds() {
        return harness.extractOutputValues().stream().filter(EnrichedAdEvent::isIvt).map(EnrichedAdEvent::eventId).toList();
    }

    @Test
    void flagsImpressionsAboveTheRateAndLaterEventsInThatMinute() throws Exception {
        for (int i = 1; i <= 5; i++) {
            send("imp" + i, EventType.IMPRESSION, T0 + i * 1_000, "bot");
        }
        send("complete", EventType.COMPLETE, T0 + 6_000, "bot");

        assertThat(flaggedIds()).containsExactly("imp4", "imp5", "complete");
        assertThat(harness.extractOutputValues()).filteredOn(EnrichedAdEvent::isIvt)
                .allSatisfy(e -> assertThat(e.ivtReason()).isEqualTo(InvalidTrafficFilter.DEVICE_IMPRESSION_RATE));
    }

    @Test
    void countsPerDeviceAndPerMinute() throws Exception {
        for (int i = 1; i <= 3; i++) {
            send("a" + i, EventType.IMPRESSION, T0 + i * 1_000, "dev-a");
            send("b" + i, EventType.IMPRESSION, T0 + i * 1_000, "dev-b");
        }
        // Next minute: the count starts again.
        for (int i = 1; i <= 3; i++) {
            send("a-next" + i, EventType.IMPRESSION, T0 + 60_000 + i * 1_000, "dev-a");
        }
        assertThat(flaggedIds()).isEmpty();
    }

    @Test
    void lateEventsStillCountTowardsTheirOwnMinute() throws Exception {
        for (int i = 1; i <= 3; i++) {
            send("imp" + i, EventType.IMPRESSION, T0 + i * 1_000, "bot");
        }
        send("next-minute", EventType.IMPRESSION, T0 + 61_000, "bot");
        send("late", EventType.IMPRESSION, T0 + 30_000, "bot");

        assertThat(flaggedIds()).containsExactly("late");
    }

    @Test
    void dropsMinuteStateOnceNothingCanArriveForIt() throws Exception {
        send("imp", EventType.IMPRESSION, T0, "dev");
        assertThat(harness.numEventTimeTimers()).isEqualTo(1);

        harness.processWatermark(T0 + 60_000 + Duration.ofMinutes(10).toMillis());

        assertThat(harness.numEventTimeTimers()).isZero();
        assertThat(harness.numKeyedStateEntries()).isZero();
    }
}
