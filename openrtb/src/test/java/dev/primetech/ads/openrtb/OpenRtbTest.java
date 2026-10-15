package dev.primetech.ads.openrtb;

import com.fasterxml.jackson.databind.JsonNode;
import dev.primetech.ads.common.DeviceType;
import dev.primetech.ads.common.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenRtbTest {

    @Test
    void requestSerializesWithSpecFieldNamesAndNoNulls() throws Exception {
        var video = new BidRequest.Video(List.of("video/mp4"), 5, 30, OpenRtb.VAST_PROTOCOLS, 1920, 1080,
                OpenRtb.PLACEMENT_INSTREAM, "pod-1", 1, 0, 90);
        var request = new BidRequest("auc-1", List.of(new BidRequest.Imp("1", video, 5.0, "USD", 1)),
                new BidRequest.App("app-1", "Streaming", "com.example.tv", null),
                new BidRequest.Device(3, new BidRequest.Geo("USA"), "hash", 0),
                BidRequest.FIRST_PRICE, 120, List.of("USD"), new BidRequest.Source("tid-1"));

        JsonNode json = Json.MAPPER.readTree(Json.MAPPER.writeValueAsBytes(request));

        assertThat(json.get("tmax").asInt()).isEqualTo(120);
        assertThat(json.at("/imp/0/video/podid").asText()).isEqualTo("pod-1");
        assertThat(json.at("/imp/0/bidfloorcur").asText()).isEqualTo("USD");
        assertThat(json.at("/device/geo/country").asText()).isEqualTo("USA");
        assertThat(json.at("/app/publisher").isMissingNode()).as("nulls are omitted").isTrue();
        assertThat(Json.MAPPER.treeToValue(json, BidRequest.class)).isEqualTo(request);
    }

    @Test
    void parsesAResponseWithUnknownExtensions() throws Exception {
        String body = """
                {"id":"auc-1","cur":"USD","seatbid":[{"seat":"dsp","bid":[
                  {"id":"b1","impid":"1","price":12.5,"adomain":["brand.example"],"cid":"c1","crid":"cr1","dur":15,
                   "nurl":"https://dsp/win?p=${AUCTION_PRICE}","ext":{"anything":true}}]}]}
                """;
        BidResponse response = Json.MAPPER.readValue(body, BidResponse.class);

        var bid = response.seatbid().get(0).bid().get(0);
        assertThat(bid.price()).isEqualTo(12.5);
        assertThat(bid.adomain()).containsExactly("brand.example");
        assertThat(bid.dur()).isEqualTo(15);
    }

    @Test
    void mapsCountriesToAlpha3() {
        assertThat(OpenRtb.alpha3("US")).isEqualTo("USA");
        assertThat(OpenRtb.alpha3("GB")).isEqualTo("GBR");
        assertThat(OpenRtb.alpha3("KR")).isEqualTo("KOR");
        assertThatThrownBy(() -> OpenRtb.alpha3("ZZ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsDeviceTypes() {
        assertThat(OpenRtb.deviceType(DeviceType.CTV)).isEqualTo(3);
        assertThat(OpenRtb.deviceType(DeviceType.WEB)).isEqualTo(2);
    }

    @Test
    void convertsMoneyAndExpandsMacros() {
        assertThat(OpenRtb.cpmToMicros(12.345678)).isEqualTo(12_345_678L);
        assertThat(OpenRtb.expandMacros("https://dsp/win?p=${AUCTION_PRICE}&a=${AUCTION_ID}", "auc-9", 12_500_000L))
                .isEqualTo("https://dsp/win?p=12.500000&a=auc-9");
    }
}
