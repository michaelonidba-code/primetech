package dev.primetech.ads.dsp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.http.JsonHttp;
import dev.primetech.ads.openrtb.BidRequest;
import dev.primetech.ads.openrtb.BidResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** HTTP front of a mock DSP: the OpenRTB bid endpoint, win notices and its own counters. */
public final class DspServer implements AutoCloseable {
    private final HttpServer server;
    private final Bidder bidder;
    private final PrometheusMeterRegistry registry;
    private final boolean simulateLatency;
    // SplittableRandom is not thread-safe; each request splits its own generator off this one.
    private final AtomicReference<SplittableRandom> seed;

    private final Counter requests;
    private final Counter bidResponses;
    private final Counter bids;
    private final Counter noBids;
    private final Counter wins;
    private final Counter winSpendMicros;

    public DspServer(int port, Bidder bidder, PrometheusMeterRegistry registry, long seed, boolean simulateLatency)
            throws IOException {
        this.bidder = bidder;
        this.registry = registry;
        this.simulateLatency = simulateLatency;
        this.seed = new AtomicReference<>(new SplittableRandom(seed));
        requests = registry.counter("dsp.bid.requests");
        bidResponses = registry.counter("dsp.bid.responses");
        bids = registry.counter("dsp.bids");
        noBids = registry.counter("dsp.nobids");
        wins = registry.counter("dsp.wins");
        winSpendMicros = registry.counter("dsp.win.spend.micros");

        server = HttpServer.create(new InetSocketAddress(port), 1024);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/openrtb2/bid", this::handleBid);
        server.createContext("/win", this::handleWin);
        server.createContext("/stats", ex -> {
            try (ex) {
                JsonHttp.json(ex, 200, stats());
            }
        });
        server.createContext("/metrics", ex -> {
            try (ex) {
                JsonHttp.send(ex, 200, "text/plain; version=0.0.4", registry.scrape().getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/health", ex -> {
            try (ex) {
                JsonHttp.json(ex, 200, Map.of("status", "UP"));
            }
        });
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(1);
    }

    private SplittableRandom random() {
        return seed.updateAndGet(SplittableRandom::split);
    }

    private void handleBid(HttpExchange ex) throws IOException {
        try (ex) {
            if (!ex.getRequestMethod().equals("POST")) {
                JsonHttp.error(ex, 405, "use POST");
                return;
            }
            byte[] body = JsonHttp.readLimited(ex.getRequestBody(), 256 * 1024);
            if (body == null) {
                JsonHttp.error(ex, 413, "request too large");
                return;
            }
            BidRequest request;
            try {
                request = Json.MAPPER.readValue(body, BidRequest.class);
            } catch (JsonProcessingException e) {
                JsonHttp.error(ex, 400, "malformed bid request");
                return;
            }
            requests.increment();
            var random = random();
            if (simulateLatency) {
                sleep(bidder.latencyMs(random));
            }
            BidResponse response = bidder.bid(request, random);
            if (response == null) {
                noBids.increment();
                JsonHttp.noContent(ex);
                return;
            }
            bidResponses.increment();
            bids.increment(response.seatbid().get(0).bid().size());
            JsonHttp.json(ex, 200, response);
        }
    }

    private void handleWin(HttpExchange ex) throws IOException {
        try (ex) {
            String price = JsonHttp.query(ex, "price");
            if (price != null) {
                wins.increment();
                winSpendMicros.increment(Math.round(Double.parseDouble(price) * 1_000_000 / 1000));
            }
            JsonHttp.noContent(ex);
        }
    }

    private Map<String, Object> stats() {
        var m = new LinkedHashMap<String, Object>();
        m.put("requests", (long) requests.count());
        m.put("bidResponses", (long) bidResponses.count());
        m.put("bids", (long) bids.count());
        m.put("noBids", (long) noBids.count());
        m.put("wins", (long) wins.count());
        m.put("winSpendMicros", (long) winSpendMicros.count());
        return m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
