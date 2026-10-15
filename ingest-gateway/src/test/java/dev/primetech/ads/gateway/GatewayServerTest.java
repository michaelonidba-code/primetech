package dev.primetech.ads.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import dev.primetech.ads.common.DeviceIdHasher;
import dev.primetech.ads.common.EventNormalizer;
import dev.primetech.ads.common.EventValidator;
import dev.primetech.ads.common.Json;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayServerTest {
    private final FakeSink sink = new FakeSink();
    private final HttpClient http = HttpClient.newHttpClient();
    private GatewayServer server;

    @BeforeEach
    void start() throws Exception {
        var config = new GatewayConfig(0, "unused", "test-secret-0123456789", 2, 4096,
                Duration.ofSeconds(1), "*");
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var metrics = new IngestMetrics(registry);
        var ingest = new IngestService(EventValidator.withDefaults(),
                new EventNormalizer(new DeviceIdHasher(config.deviceIdSecret())),
                sink, metrics, System::currentTimeMillis, config.publishTimeout());
        server = new GatewayServer(config, ingest, metrics, registry);
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/v1/events"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }

    private static String validEvent(String id) {
        return """
                {"eventId":"%s","type":"COMPLETE","eventTimeMs":%d,"sessionId":"s1","adId":"a1",
                 "creativeId":"c1","campaignId":"camp1","lineItemId":"li1","deviceId":"d1",
                 "deviceType":"CTV","country":"US","consent":true}
                """.formatted(id, System.currentTimeMillis());
    }

    @Test
    void acceptsBatchAndReportsStats() throws Exception {
        var response = post("{\"events\":[" + validEvent("e1") + "," + validEvent("e2") + "]}");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = Json.MAPPER.readTree(response.body());
        assertThat(body.get("accepted").asInt()).isEqualTo(2);
        assertThat(sink.events).hasSize(2);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("*");

        JsonNode stats = Json.MAPPER.readTree(get("/stats").body());
        assertThat(stats.get("accepted").asLong()).isEqualTo(2);
        assertThat(stats.get("acceptedByType").get("COMPLETE").asLong()).isEqualTo(2);
    }

    @Test
    void rejectsMalformedOversizedAndEmptyRequests() throws Exception {
        assertThat(post("{not json").statusCode()).isEqualTo(400);
        assertThat(post("{\"events\":[]}").statusCode()).isEqualTo(400);
        assertThat(post("{\"events\":[" + validEvent("1") + "," + validEvent("2") + "," + validEvent("3") + "]}")
                .statusCode()).as("more than maxEventsPerBatch").isEqualTo(413);
        assertThat(post("{\"events\":[\"" + "x".repeat(5000) + "\"]}").statusCode())
                .as("body over maxBodyBytes").isEqualTo(413);
        assertThat(get("/v1/events").statusCode()).isEqualTo(405);
    }

    @Test
    void returns503WithRetryAfterWhenKafkaIsDown() throws Exception {
        sink.failing = true;
        var response = post("{\"events\":[" + validEvent("e1") + "]}");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
    }

    @Test
    void exposesPrometheusMetricsAndHealth() throws Exception {
        post("{\"events\":[" + validEvent("e1") + "]}");

        assertThat(get("/health").statusCode()).isEqualTo(200);
        assertThat(get("/metrics").body())
                .contains("ads_ingest_events_accepted_total{type=\"COMPLETE\"} 1.0")
                .contains("ads_ingest_events_rejected_total{reason=\"MISSING_PRICE\"}")
                .contains("ads_ingest_request_latency_seconds");
    }
}
