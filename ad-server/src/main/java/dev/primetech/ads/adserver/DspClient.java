package dev.primetech.ads.adserver;

import dev.primetech.ads.common.Json;
import dev.primetech.ads.openrtb.BidRequest;
import dev.primetech.ads.openrtb.BidResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * Calls one DSP's OpenRTB endpoint. Never fails: every outcome, including a missed deadline,
 * becomes a {@link Result}, so one bad bidder cannot break or slow down an auction.
 *
 * <p>The auction deadline and the HTTP request lifetime are deliberately different. The auction
 * stops waiting at {@code tmax}, but the HTTP exchange may run on (up to {@link #REQUEST_TIMEOUT})
 * so a late response returns its connection to the pool. Aborting at {@code tmax} closes the
 * connection instead, and under load the reconnects cost enough CPU to cause more timeouts: in
 * profiling, connection churn in the HTTP client was the largest single cost.
 */
public final class DspClient {
    public enum Status { BID, NO_BID, TIMEOUT, ERROR }

    public record Result(String dsp, Status status, List<BidResponse.Bid> bids, long latencyMs) {
    }

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);

    private final String name;
    private final URI endpoint;
    private final HttpClient http;

    public DspClient(String name, URI endpoint, HttpClient http) {
        this.name = name;
        this.endpoint = endpoint;
        this.http = http;
    }

    /** One client per process: HTTP/1.1 keep-alive connections, virtual threads for the work. */
    public static HttpClient sharedHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(500))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public String name() {
        return name;
    }

    /** Serializes once; the same bytes go to every DSP. */
    public static byte[] serialize(BidRequest request) {
        try {
            return Json.MAPPER.writeValueAsBytes(request);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialize bid request", e);
        }
    }

    /** Completes within {@code deadline}, as TIMEOUT if the DSP has not answered by then. */
    public CompletableFuture<Result> bid(byte[] body, Duration deadline) {
        long start = System.nanoTime();
        var httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-openrtb-version", "2.6")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        // copy(): the timeout completes our copy, leaving the HTTP exchange itself running.
        return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .copy()
                .orTimeout(deadline.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .handle((response, error) -> {
                    long latency = (System.nanoTime() - start) / 1_000_000;
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                        boolean timeout = cause instanceof HttpTimeoutException || cause instanceof TimeoutException;
                        return new Result(name, timeout ? Status.TIMEOUT : Status.ERROR, List.of(), latency);
                    }
                    return parse(response, latency);
                });
    }

    private Result parse(HttpResponse<byte[]> response, long latency) {
        if (response.statusCode() == 204) {
            return new Result(name, Status.NO_BID, List.of(), latency);
        }
        if (response.statusCode() != 200) {
            return new Result(name, Status.ERROR, List.of(), latency);
        }
        try {
            BidResponse parsed = Json.MAPPER.readValue(response.body(), BidResponse.class);
            List<BidResponse.Bid> bids = parsed.seatbid() == null ? List.of()
                    : parsed.seatbid().stream().flatMap(s -> s.bid() == null ? java.util.stream.Stream.empty() : s.bid().stream()).toList();
            return new Result(name, bids.isEmpty() ? Status.NO_BID : Status.BID, bids, latency);
        } catch (IOException e) {
            return new Result(name, Status.ERROR, List.of(), latency);
        }
    }

    /** Fire-and-forget GET of a win notice. */
    public void notify(String url) {
        http.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }
}
