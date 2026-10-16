package dev.primetech.ads.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.primetech.ads.common.ClientEvent;
import dev.primetech.ads.common.IngestRequest;
import dev.primetech.ads.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Sends batches the way a well-behaved player SDK would: bounded concurrency, and the whole batch
 * retried with exponential backoff on 5xx, 429 or network errors. Those retries are another real
 * source of duplicates.
 */
final class GatewayClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GatewayClient.class);
    private static final int MAX_ATTEMPTS = 5;

    final LongAdder sentEvents = new LongAdder();
    final LongAdder accepted = new LongAdder();
    final LongAdder rejected = new LongAdder();
    final LongAdder retries = new LongAdder();
    final LongAdder failedEvents = new LongAdder();
    private final ConcurrentLinkedQueue<Long> latenciesMicros = new ConcurrentLinkedQueue<>();

    private final URI endpoint;
    private final HttpClient http;
    private final Semaphore inFlight;
    private final int concurrency;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    GatewayClient(String gatewayUrl, int concurrency) {
        this.endpoint = URI.create(gatewayUrl.replaceAll("/+$", "") + "/v1/events");
        this.concurrency = concurrency;
        this.inFlight = new Semaphore(concurrency);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(executor)
                .build();
    }

    /** Blocks while {@code concurrency} batches are already in flight; that is the backpressure. */
    void submit(List<ClientEvent> batch) throws InterruptedException {
        inFlight.acquire();
        executor.execute(() -> {
            try {
                sendWithRetry(batch);
            } finally {
                inFlight.release();
            }
        });
    }

    int inFlight() {
        return concurrency - inFlight.availablePermits();
    }

    /** Waits for in-flight batches to finish. */
    void drain(Duration timeout) throws InterruptedException {
        if (inFlight.tryAcquire(concurrency, timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            inFlight.release(concurrency);
        }
    }

    /** p99 of request latency since the last call, in milliseconds. */
    double drainP99Millis() {
        var samples = new java.util.ArrayList<Long>();
        Long v;
        while ((v = latenciesMicros.poll()) != null) {
            samples.add(v);
        }
        if (samples.isEmpty()) {
            return 0;
        }
        samples.sort(null);
        return samples.get((int) Math.min(samples.size() - 1, Math.ceil(samples.size() * 0.99) - 1)) / 1000.0;
    }

    private void sendWithRetry(List<ClientEvent> batch) {
        byte[] body;
        try {
            body = Json.MAPPER.writeValueAsBytes(new IngestRequest(batch));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long start = System.nanoTime();
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                latenciesMicros.add((System.nanoTime() - start) / 1000);
                int status = response.statusCode();
                if (status == 200) {
                    JsonNode result = Json.MAPPER.readTree(response.body());
                    sentEvents.add(batch.size());
                    accepted.add(result.get("accepted").asLong());
                    rejected.add(result.get("rejected").asLong());
                    return;
                }
                if (status != 429 && status < 500) {
                    log.warn("batch dropped, gateway answered {}: {}", status, new String(response.body()));
                    failedEvents.add(batch.size());
                    return;
                }
            } catch (IOException e) {
                log.debug("send attempt {} failed: {}", attempt, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failedEvents.add(batch.size());
                return;
            }
            if (attempt < MAX_ATTEMPTS) {
                retries.increment();
                sleepBackoff(attempt);
            }
        }
        log.warn("batch of {} events dropped after {} attempts", batch.size(), MAX_ATTEMPTS);
        failedEvents.add(batch.size());
    }

    private static void sleepBackoff(int attempt) {
        try {
            // 100ms, 200ms, 400ms, 800ms, with jitter so retries from many clients spread out.
            long base = 100L << (attempt - 1);
            Thread.sleep(base + (long) (Math.random() * base));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
