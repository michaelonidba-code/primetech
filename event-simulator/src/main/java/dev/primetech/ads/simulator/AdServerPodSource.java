package dev.primetech.ads.simulator;

import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.adserving.AdRequest;
import dev.primetech.ads.common.adserving.AdResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Asks the ad server for each break, asynchronously, like a player starting an ad pod. A cap on
 * requests in flight stops the simulator from piling up work when the ad server falls behind:
 * over the cap, the break is skipped (and counted) rather than queued.
 */
final class AdServerPodSource implements PodSource, AutoCloseable {
    private static final int MAX_AD_SECONDS = 30;

    final LongAdder requests = new LongAdder();
    final LongAdder adsReceived = new LongAdder();
    final LongAdder failures = new LongAdder();
    final LongAdder skipped = new LongAdder();

    private final URI endpoint;
    private final HttpClient http;
    private final Semaphore inFlight;

    AdServerPodSource(String adServerUrl, int maxInFlight) {
        this.endpoint = URI.create(adServerUrl.replaceAll("/+$", "") + "/v1/ad-requests");
        this.inFlight = new Semaphore(maxInFlight);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Override
    public void request(SessionPlanner.Viewer v, Consumer<List<SessionPlanner.PodAd>> onPod) {
        if (!inFlight.tryAcquire()) {
            skipped.increment();
            onPod.accept(List.of());
            return;
        }
        byte[] body;
        try {
            body = Json.MAPPER.writeValueAsBytes(new AdRequest(v.sessionId(), v.deviceId(), v.consent(),
                    v.deviceType(), v.country(), v.slots(), MAX_AD_SECONDS, v.slots() * MAX_AD_SECONDS));
        } catch (IOException e) {
            inFlight.release();
            throw new IllegalStateException(e);
        }
        requests.increment();
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((response, error) -> {
            inFlight.release();
            List<SessionPlanner.PodAd> pod = List.of();
            if (error == null && response.statusCode() == 200) {
                try {
                    pod = Json.MAPPER.readValue(response.body(), AdResponse.class).ads().stream()
                            .map(a -> new SessionPlanner.PodAd(a.adId(), a.creativeId(), a.campaignId(), a.lineItemId(),
                                    a.durationSeconds(), a.priceMicros(), a.auctionId()))
                            .toList();
                } catch (IOException e) {
                    failures.increment();
                }
            } else {
                failures.increment();
            }
            adsReceived.add(pod.size());
            onPod.accept(pod);
        });
    }

    String summary() {
        return "adRequests=%d adsReceived=%d failures=%d skipped=%d".formatted(
                requests.sum(), adsReceived.sum(), failures.sum(), skipped.sum());
    }

    @Override
    public void close() {
        http.close();
    }
}
