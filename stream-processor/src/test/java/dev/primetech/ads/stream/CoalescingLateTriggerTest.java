package dev.primetech.ads.stream;

import org.apache.flink.streaming.api.windowing.triggers.TriggerResult;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.runtime.operators.windowing.TriggerTestHarness;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CoalescingLateTriggerTest {
    private static final TimeWindow MINUTE = new TimeWindow(0, 60_000);

    private static TriggerTestHarness<Object, TimeWindow> harness(Duration delay) throws Exception {
        return new TriggerTestHarness<>(new CoalescingLateTrigger(delay), new TimeWindow.Serializer());
    }

    @Test
    void firesOnceWhenTheWatermarkPassesTheWindow() throws Exception {
        var h = harness(Duration.ofSeconds(5));
        assertThat(h.processElement(new StreamRecord<>("a", 1_000), MINUTE)).isEqualTo(TriggerResult.CONTINUE);
        assertThat(h.processElement(new StreamRecord<>("b", 2_000), MINUTE)).isEqualTo(TriggerResult.CONTINUE);
        assertThat(h.numEventTimeTimers(MINUTE)).isEqualTo(1);

        assertThat(h.advanceWatermark(59_998)).isEmpty();
        assertThat(h.advanceWatermark(59_999, MINUTE)).isEqualTo(TriggerResult.FIRE);
    }

    @Test
    void coalescesABurstOfLateEventsIntoOneFiring() throws Exception {
        var h = harness(Duration.ofSeconds(5));
        h.processElement(new StreamRecord<>("a", 1_000), MINUTE);
        h.advanceWatermark(70_000, MINUTE);
        h.advanceProcessingTime(100_000);

        for (int i = 0; i < 50; i++) {
            assertThat(h.processElement(new StreamRecord<>("late" + i, 30_000), MINUTE)).isEqualTo(TriggerResult.CONTINUE);
        }
        assertThat(h.numProcessingTimeTimers(MINUTE)).isEqualTo(1);

        assertThat(h.advanceProcessingTime(104_999)).isEmpty();
        assertThat(h.advanceProcessingTime(105_000, MINUTE)).isEqualTo(TriggerResult.FIRE);

        // A later late event schedules a new firing.
        h.processElement(new StreamRecord<>("later", 30_000), MINUTE);
        assertThat(h.numProcessingTimeTimers(MINUTE)).isEqualTo(1);
    }

    @Test
    void zeroDelayFiresForEachLateEvent() throws Exception {
        var h = harness(Duration.ZERO);
        h.processElement(new StreamRecord<>("a", 1_000), MINUTE);
        h.advanceWatermark(70_000, MINUTE);

        assertThat(h.processElement(new StreamRecord<>("late", 30_000), MINUTE)).isEqualTo(TriggerResult.FIRE);
        assertThat(h.numProcessingTimeTimers()).isZero();
    }

    @Test
    void clearRemovesTimersAndState() throws Exception {
        var h = harness(Duration.ofSeconds(5));
        h.processElement(new StreamRecord<>("a", 1_000), MINUTE);
        h.advanceWatermark(70_000, MINUTE);
        h.processElement(new StreamRecord<>("late", 30_000), MINUTE);

        h.clearTriggerState(MINUTE);

        assertThat(h.numEventTimeTimers()).isZero();
        assertThat(h.numProcessingTimeTimers()).isZero();
        assertThat(h.numStateEntries()).isZero();
    }
}
