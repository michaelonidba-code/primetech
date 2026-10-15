package dev.primetech.ads.stream;

import dev.primetech.ads.common.AdEvent;
import dev.primetech.ads.common.EventType;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.streaming.util.ProcessFunctionTestHarnesses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static dev.primetech.ads.stream.Events.T0;
import static dev.primetech.ads.stream.Events.event;
import static org.assertj.core.api.Assertions.assertThat;

class DeduplicateTest {
    private KeyedOneInputStreamOperatorTestHarness<String, AdEvent, AdEvent> harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = ProcessFunctionTestHarnesses.forKeyedProcessFunction(
                new Deduplicate(Duration.ofMinutes(30)), AdEvent::eventId, Types.STRING);
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    @Test
    void passesFirstOccurrenceAndDropsRepeats() throws Exception {
        var a = event("a", EventType.IMPRESSION, T0, "camp-1", "d1");
        var b = event("b", EventType.MIDPOINT, T0, "camp-1", "d1");

        harness.processElement(a, T0);
        harness.processElement(a, T0 + 1_000);
        harness.processElement(b, T0);
        harness.processElement(a, T0 + 9_000);

        assertThat(harness.extractOutputValues()).containsExactly(a, b);
    }

    @Test
    void forgetsIdsAfterTheTtl() throws Exception {
        var a = event("a", EventType.IMPRESSION, T0, "camp-1", "d1");
        harness.setStateTtlProcessingTime(0);
        harness.processElement(a, T0);

        harness.setStateTtlProcessingTime(Duration.ofMinutes(29).toMillis());
        harness.processElement(a, T0);
        assertThat(harness.extractOutputValues()).hasSize(1);

        harness.setStateTtlProcessingTime(Duration.ofMinutes(31).toMillis());
        harness.processElement(a, T0);
        assertThat(harness.extractOutputValues()).as("a repeat after the TTL is let through").hasSize(2);
    }
}
