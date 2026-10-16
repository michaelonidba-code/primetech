package dev.primetech.ads.common;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichedAdEventTest {
    private static final AdEvent IMPRESSION = new AdEvent(1, "e1", EventType.IMPRESSION, 1_000L, 1_100L, "s1",
            "ad", "cr", "camp-1", "li-1", "hash", DeviceType.CTV, "US", true, 40_000L);
    private static final CampaignMetadata META = new CampaignMetadata("camp-1", "Spring Launch", "adv-1", "Harbor Bank");

    @Test
    void enrichmentCopiesEventAndMetadata() {
        var e = EnrichedAdEvent.of(IMPRESSION, META, 2_000L);

        assertThat(e.campaignName()).isEqualTo("Spring Launch");
        assertThat(e.advertiserName()).isEqualTo("Harbor Bank");
        assertThat(e.processedTimeMs()).isEqualTo(2_000L);
        assertThat(e.billableMicros()).isEqualTo(40_000L);
        assertThat(e.isIvt()).isFalse();
    }

    @Test
    void ivtEventsAreNotBillable() {
        var e = EnrichedAdEvent.of(IMPRESSION, META, 2_000L).withIvt("DEVICE_RATE");

        assertThat(e.isIvt()).isTrue();
        assertThat(e.billableMicros()).isZero();
        assertThat(e.priceMicros()).as("the price is kept for reporting").isEqualTo(40_000L);
    }

    @Test
    void missingMetadataLeavesFieldsNull() {
        var e = EnrichedAdEvent.of(IMPRESSION, null, 2_000L);
        assertThat(e.campaignName()).isNull();
        assertThat(e.advertiserId()).isNull();
    }

    @Test
    void jsonIsFlatWithAnIvtFlagForAnalytics() throws Exception {
        var e = EnrichedAdEvent.of(IMPRESSION, META, 2_000L).withIvt("DEVICE_RATE");
        JsonNode json = Json.MAPPER.readTree(Json.MAPPER.writeValueAsBytes(e));

        assertThat(json.get("ivt").asBoolean()).isTrue();
        assertThat(json.get("ivtReason").asText()).isEqualTo("DEVICE_RATE");
        assertThat(json.get("campaignName").asText()).isEqualTo("Spring Launch");
        assertThat(json.has("billableMicros")).isFalse();
        assertThat(Json.MAPPER.treeToValue(json, EnrichedAdEvent.class)).isEqualTo(e);
    }
}
