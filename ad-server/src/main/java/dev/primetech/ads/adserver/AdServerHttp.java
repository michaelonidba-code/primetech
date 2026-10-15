package dev.primetech.ads.adserver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.adserving.AdRequest;
import dev.primetech.ads.common.http.JsonHttp;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * HTTP front of the ad server, on virtual threads like the gateway.
 *
 * <p>Admission control: at most {@code maxInFlight} decisions run at once; beyond that a request
 * gets 503 straight away. Without it, an overloaded server keeps accepting work it can no longer
 * finish before clients give up, and abandoned connections pile up until it runs out of file
 * descriptors and stops answering altogether (this happened in testing). Shedding turns overload
 * into unfilled breaks, which players already handle.
 */
public final class AdServerHttp implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AdServerHttp.class);

    private final HttpServer server;
    private final AdDecisionService service;
    private final AdServerMetrics metrics;
    private final String corsOrigin;
    private final Semaphore inFlight;

    public AdServerHttp(int port, AdDecisionService service, AdServerMetrics metrics, PrometheusMeterRegistry registry,
                        String corsOrigin, int maxInFlight) throws IOException {
        this.service = service;
        this.metrics = metrics;
        this.corsOrigin = corsOrigin;
        this.inFlight = new Semaphore(maxInFlight);
        server = HttpServer.create(new InetSocketAddress(port), 2048);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/ad-requests", this::handleAdRequest);
        server.createContext("/stats", ex -> get(ex, () -> JsonHttp.json(ex, 200, metrics.snapshot())));
        server.createContext("/metrics", ex -> get(ex, () -> JsonHttp.send(ex, 200, "text/plain; version=0.0.4",
                registry.scrape().getBytes(StandardCharsets.UTF_8))));
        server.createContext("/health", ex -> get(ex, () -> JsonHttp.json(ex, 200, Map.of("status", "UP"))));
    }

    public void start() {
        server.start();
        log.info("ad server listening on :{}", port());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(2);
    }

    private void handleAdRequest(HttpExchange ex) throws IOException {
        try (ex) {
            JsonHttp.cors(ex, corsOrigin);
            if (ex.getRequestMethod().equals("OPTIONS")) {
                JsonHttp.noContent(ex);
                return;
            }
            if (!ex.getRequestMethod().equals("POST")) {
                JsonHttp.error(ex, 405, "use POST");
                return;
            }
            byte[] body = JsonHttp.readLimited(ex.getRequestBody(), 16 * 1024);
            if (body == null) {
                JsonHttp.error(ex, 413, "request too large");
                return;
            }
            AdRequest request;
            try {
                request = Json.MAPPER.readValue(body, AdRequest.class);
            } catch (JsonProcessingException e) {
                JsonHttp.error(ex, 400, "malformed JSON");
                return;
            }
            String problem = service.validate(request);
            if (problem != null) {
                JsonHttp.error(ex, 400, problem);
                return;
            }
            if (!inFlight.tryAcquire()) {
                metrics.shed();
                ex.getResponseHeaders().set("Retry-After", "1");
                JsonHttp.error(ex, 503, "overloaded");
                return;
            }
            try {
                JsonHttp.json(ex, 200, service.decide(request));
            } finally {
                inFlight.release();
            }
        } catch (RuntimeException e) {
            log.error("unexpected error handling ad request", e);
            JsonHttp.error(ex, 500, "internal error");
        }
    }

    private void get(HttpExchange ex, IoAction action) throws IOException {
        try (ex) {
            JsonHttp.cors(ex, corsOrigin);
            if (!ex.getRequestMethod().equals("GET")) {
                JsonHttp.error(ex, 405, "use GET");
                return;
            }
            action.run();
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
