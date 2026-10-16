package dev.primetech.ads.adserver;

import com.sun.net.httpserver.HttpServer;
import dev.primetech.ads.common.DeviceIdHasher;
import dev.primetech.ads.common.catalog.AdCatalog;
import dev.primetech.ads.openrtb.BidRequest;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class AdServerHttpTest {
    private static final String BODY = """
            {"sessionId":"s1","deviceId":"d1","consent":true,"deviceType":"CTV","country":"US","slots":2}
            """;

    private HttpServer slowDsp;
    private AdServerHttp server;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        server.close();
        slowDsp.stop(0);
    }

    private void start(int maxInFlight) throws Exception {
        slowDsp = HttpServer.create(new InetSocketAddress(0), 0);
        slowDsp.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        slowDsp.createContext("/", ex -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        slowDsp.start();

        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var metrics = new AdServerMetrics(registry, List.of("slow"));
        var dsp = new DspClient("slow", URI.create("http://localhost:" + slowDsp.getAddress().getPort() + "/"),
                DspClient.sharedHttpClient());
        var service = new AdDecisionService(
                new AdDecisionService.Settings(5, Duration.ofMillis(700), 5_000_000, BidRequest.FIRST_PRICE, 4),
                new DirectDemand(AdCatalog.generate(10, new SplittableRandom(1)), PacingView.fixed(1.0)), List.of(dsp),
                new InMemoryFrequencyCaps(), new AdDecisionServiceTest.RecordingSink(),
                new DeviceIdHasher("test-secret-0123456789"), metrics);
        server = new AdServerHttp(0, service, metrics, registry, "*", maxInFlight);
        server.start();
    }

    private CompletableFuture<HttpResponse<String>> post(String body) {
        return http.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/v1/ad-requests"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void shedsLoadInsteadOfQueueing() throws Exception {
        start(1);
        var first = post(BODY);
        Thread.sleep(150); // the first request is now waiting on the slow DSP
        long start = System.nanoTime();
        var second = post(BODY).get();
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(second.statusCode()).isEqualTo(503);
        assertThat(second.headers().firstValue("Retry-After")).contains("1");
        assertThat(ms).as("rejected immediately, not after the slow DSP").isLessThan(300);
        assertThat(first.get().statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        start(4);
        assertThat(post("{not json").get().statusCode()).isEqualTo(400);
        assertThat(post(BODY.replace("\"US\"", "\"usa\"")).get().statusCode()).isEqualTo(400);
        assertThat(post(BODY.replace("\"slots\":2", "\"slots\":9")).get().statusCode()).isEqualTo(400);
    }
}
