package dev.primetech.ads.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.primetech.ads.common.IngestRequest;
import dev.primetech.ads.common.Json;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP front door. Uses the JDK server on virtual threads: each request blocks while waiting for
 * Kafka acks, which is cheap on a virtual thread and keeps the code straightforward.
 */
public final class GatewayServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final HttpServer server;
    private final IngestService ingest;
    private final IngestMetrics metrics;
    private final PrometheusMeterRegistry registry;
    private final GatewayConfig config;

    public GatewayServer(GatewayConfig config, IngestService ingest, IngestMetrics metrics,
                         PrometheusMeterRegistry registry) throws IOException {
        this.config = config;
        this.ingest = ingest;
        this.metrics = metrics;
        this.registry = registry;
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 1024);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/events", this::handleEvents);
        server.createContext("/stats", ex -> handleGet(ex, () -> json(ex, 200, metrics.snapshot())));
        server.createContext("/metrics", ex -> handleGet(ex,
                () -> send(ex, 200, "text/plain; version=0.0.4", registry.scrape().getBytes(StandardCharsets.UTF_8))));
        server.createContext("/health", ex -> handleGet(ex, () -> json(ex, 200, Map.of("status", "UP"))));
    }

    public void start() {
        server.start();
        log.info("ingest gateway listening on :{}", port());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(2);
    }

    private void handleEvents(HttpExchange ex) throws IOException {
        long start = System.nanoTime();
        try (ex) {
            addCors(ex);
            if (ex.getRequestMethod().equals("OPTIONS")) {
                send(ex, 204, null, null);
                return;
            }
            if (!ex.getRequestMethod().equals("POST")) {
                error(ex, 405, "use POST");
                return;
            }
            byte[] body = readLimited(ex.getRequestBody(), config.maxBodyBytes());
            if (body == null) {
                error(ex, 413, "body larger than " + config.maxBodyBytes() + " bytes");
                return;
            }
            IngestRequest request;
            try {
                request = Json.MAPPER.readValue(body, IngestRequest.class);
            } catch (JsonProcessingException e) {
                error(ex, 400, "malformed JSON");
                return;
            }
            if (request.events() == null || request.events().isEmpty()) {
                error(ex, 400, "events must be a non-empty array");
                return;
            }
            if (request.events().size() > config.maxEventsPerBatch()) {
                error(ex, 413, "at most " + config.maxEventsPerBatch() + " events per batch");
                return;
            }
            try {
                json(ex, 200, ingest.ingest(request.events()));
            } catch (IngestService.PublishFailedException e) {
                log.warn("publish failed, asking client to retry: {}", e.getCause().toString());
                ex.getResponseHeaders().set("Retry-After", "1");
                error(ex, 503, "event store unavailable, retry the batch");
            }
        } catch (RuntimeException e) {
            log.error("unexpected error handling /v1/events", e);
            error(ex, 500, "internal error");
        } finally {
            metrics.recordLatency(System.nanoTime() - start);
        }
    }

    private void handleGet(HttpExchange ex, IoAction action) throws IOException {
        try (ex) {
            addCors(ex);
            if (!ex.getRequestMethod().equals("GET")) {
                error(ex, 405, "use GET");
                return;
            }
            action.run();
        }
    }

    private void addCors(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", config.corsOrigin());
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
    }

    /** Reads at most {@code limit} bytes; returns null if the body is larger. */
    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        byte[] bytes = in.readNBytes(limit + 1);
        return bytes.length > limit ? null : bytes;
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, Map.of("error", message));
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json", Json.MAPPER.writeValueAsBytes(body));
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
