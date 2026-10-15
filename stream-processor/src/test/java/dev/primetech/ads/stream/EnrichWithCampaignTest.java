package dev.primetech.ads.stream;

import dev.primetech.ads.common.AdEvent;
import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.EnrichedAdEvent;
import dev.primetech.ads.common.EventType;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.util.KeyedBroadcastOperatorTestHarness;
import org.apache.flink.streaming.util.ProcessFunctionTestHarnesses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static dev.primetech.ads.stream.Events.CAMP_1;
import static dev.primetech.ads.stream.Events.CAMP_2;
import static dev.primetech.ads.stream.Events.T0;
import static dev.primetech.ads.stream.Events.event;
import static org.assertj.core.api.Assertions.assertThat;

class EnrichWithCampaignTest {
    private KeyedBroadcastOperatorTestHarness<String, AdEvent, CampaignMetadata, EnrichedAdEvent> harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = ProcessFunctionTestHarnesses.forKeyedBroadcastProcessFunction(
                new EnrichWithCampaign(Duration.ofSeconds(60)), AdEvent::campaignId, Types.STRING,
                EnrichWithCampaign.METADATA);
        harness.setProcessingTime(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    @Test
    void enrichesWhenMetadataIsKnown() throws Exception {
        harness.processBroadcastElement(CAMP_1, T0);
        harness.processElement(event("a", EventType.IMPRESSION, T0, "camp-1", "d"), T0);

        assertThat(harness.extractOutputValues()).singleElement().satisfies(e -> {
            assertThat(e.campaignName()).isEqualTo("Spring Launch");
            assertThat(e.advertiserName()).isEqualTo("Harbor Bank");
        });
    }

    @Test
    void buffersEventsUntilTheirCampaignArrives() throws Exception {
        harness.processElement(event("a", EventType.IMPRESSION, T0, "camp-1", "d"), T0);
        harness.processElement(event("b", EventType.MIDPOINT, T0, "camp-1", "d"), T0);
        harness.processElement(event("c", EventType.IMPRESSION, T0, "camp-2", "d"), T0);
        assertThat(harness.extractOutputValues()).isEmpty();

        harness.processBroadcastElement(CAMP_1, T0);
        assertThat(harness.extractOutputValues()).extracting(EnrichedAdEvent::eventId).containsExactly("a", "b");

        harness.processBroadcastElement(CAMP_2, T0);
        assertThat(harness.extractOutputValues()).extracting(EnrichedAdEvent::eventId).containsExactly("a", "b", "c");
        assertThat(harness.extractOutputValues()).allSatisfy(e -> assertThat(e.campaignName()).isNotNull());
    }

    @Test
    void releasesUnenrichedAfterMaxWait() throws Exception {
        harness.processElement(event("a", EventType.IMPRESSION, T0, "camp-unknown", "d"), T0);

        harness.setProcessingTime(59_000);
        assertThat(harness.extractOutputValues()).isEmpty();

        harness.setProcessingTime(60_000);
        assertThat(harness.extractOutputValues()).singleElement().satisfies(e -> {
            assertThat(e.eventId()).isEqualTo("a");
            assertThat(e.campaignName()).isNull();
        });
    }

    @Test
    void releasedEventsAreNotEmittedTwiceWhenTheTimerFires() throws Exception {
        harness.processElement(event("a", EventType.IMPRESSION, T0, "camp-1", "d"), T0);
        harness.processBroadcastElement(CAMP_1, T0);
        harness.setProcessingTime(120_000);

        assertThat(harness.extractOutputValues()).hasSize(1);
    }
}
