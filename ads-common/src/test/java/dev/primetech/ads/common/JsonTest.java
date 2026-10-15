package dev.primetech.ads.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonTest {
    @Test
    void unknownEnumAndExtraFieldsDoNotFailTheBatch() throws Exception {
        String body = """
                {"events":[{"eventId":"e1","type":"SKIP","newField":1},{"eventId":"e2","type":"CLICK"}]}
                """;
        IngestRequest req = Json.MAPPER.readValue(body, IngestRequest.class);

        assertThat(req.events()).hasSize(2);
        assertThat(req.events().get(0).type()).isNull();
        assertThat(req.events().get(1).type()).isEqualTo(EventType.CLICK);
    }

    @Test
    void roundTripsAdEvent() throws Exception {
        var event = new AdEvent(1, "e1", EventType.IMPRESSION, 1L, 2L, "s", "a", "c", "camp", "li",
                null, DeviceType.CTV, "US", false, 100L);
        String json = Json.MAPPER.writeValueAsString(event);

        assertThat(json).doesNotContain("deviceIdHash");
        assertThat(Json.MAPPER.readValue(json, AdEvent.class)).isEqualTo(event);
    }
}
