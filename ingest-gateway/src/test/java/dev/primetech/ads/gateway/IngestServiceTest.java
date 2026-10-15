package dev.primetech.ads.gateway;

import dev.primetech.ads.common.ClientEvent;
import dev.primetech.ads.common.DeviceIdHasher;
import dev.primetech.ads.common.DeviceType;
import dev.primetech.ads.common.EventNormalizer;
import dev.primetech.ads.common.EventType;
import dev.primetech.ads.common.EventValidator;
import dev.primetech.ads.common.RejectReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestServiceTest {
    static final long NOW = 1_760_000_000_000L;

    private final FakeSink sink = new FakeSink();
    private final IngestMetrics metrics = new IngestMetrics(new SimpleMeterRegistry());
    private final IngestService service = new IngestService(
            EventValidator.withDefaults(),
            new EventNormalizer(new DeviceIdHasher("test-secret-0123456789")),
            sink, metrics, () -> NOW, Duration.ofSeconds(1));

    static ClientEvent event(String id, EventType type, Long price) {
        return new ClientEvent(id, type, NOW, "sess-1", "ad-1", "cr-1", "camp-1", "li-1",
                "raw-device", DeviceType.CTV, "US", true, price);
    }

    @Test
    void publishesValidEventsAndDeadLettersInvalidOnes() throws Exception {
        var response = service.ingest(List.of(
                event("a", EventType.IMPRESSION, 1_000L),
                event("b", EventType.MIDPOINT, 5L),
                event("c", EventType.COMPLETE, null)));

        assertThat(response.accepted()).isEqualTo(2);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.errors()).singleElement().satisfies(r -> {
            assertThat(r.index()).isEqualTo(1);
            assertThat(r.eventId()).isEqualTo("b");
            assertThat(r.reason()).isEqualTo(RejectReason.PRICE_ON_NON_BILLABLE);
        });
        assertThat(sink.events).extracting(e -> e.eventId()).containsExactly("a", "c");
        assertThat(sink.events).allSatisfy(e -> assertThat(e.receivedTimeMs()).isEqualTo(NOW));
        assertThat(sink.deadLetters).singleElement().satisfies(d -> {
            assertThat(d.reason()).isEqualTo(RejectReason.PRICE_ON_NON_BILLABLE);
            assertThat(d.event().deviceId()).as("DLQ must not keep raw device ids").isNull();
        });

        var snapshot = metrics.snapshot();
        assertThat(snapshot.accepted()).isEqualTo(2);
        assertThat(snapshot.rejected()).isEqualTo(1);
        assertThat(snapshot.acceptedByType()).containsEntry("IMPRESSION", 1L).containsEntry("COMPLETE", 1L);
    }

    @Test
    void nullEntryIsRejectedNotFatal() throws Exception {
        var response = service.ingest(Arrays.asList(null, event("a", EventType.CLICK, null)));

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.errors()).singleElement()
                .satisfies(r -> assertThat(r.reason()).isEqualTo(RejectReason.MISSING_FIELD));
    }

    @Test
    void failsWholeBatchWhenSinkFailsAndCountsNothing() {
        sink.failing = true;

        assertThatThrownBy(() -> service.ingest(List.of(event("a", EventType.CLICK, null))))
                .isInstanceOf(IngestService.PublishFailedException.class);
        var snapshot = metrics.snapshot();
        assertThat(snapshot.accepted()).isZero();
        assertThat(snapshot.publishFailures()).isEqualTo(1);
    }
}
