package dev.primetech.ads.common.http;

import com.sun.net.httpserver.HttpExchange;
import dev.primetech.ads.common.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/** Small helpers for JSON services on the JDK HTTP server. */
public final class JsonHttp {
    private JsonHttp() {
    }

    /** Reads at most {@code limit} bytes; returns null if the body is larger. */
    public static byte[] readLimited(InputStream in, int limit) throws IOException {
        byte[] bytes = in.readNBytes(limit + 1);
        return bytes.length > limit ? null : bytes;
    }

    public static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json", Json.MAPPER.writeValueAsBytes(body));
    }

    public static void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, Map.of("error", message));
    }

    public static void noContent(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(204, -1);
    }

    public static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Adds CORS headers. {@code allowed} is {@code *} or a comma-separated list of origins
     * (e.g. {@code https://example.org,https://www.example.org}); a listed request origin is echoed back.
     */
    public static void cors(HttpExchange ex, String allowed) {
        var h = ex.getResponseHeaders();
        String origin = allowedOrigin(allowed, ex.getRequestHeaders().getFirst("Origin"));
        h.set("Access-Control-Allow-Origin", origin);
        if (!origin.equals("*")) {
            h.add("Vary", "Origin");
        }
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
    }

    /**
     * The Access-Control-Allow-Origin value for a request: {@code *} when any origin is allowed, the
     * request's origin when it is listed, otherwise the first listed origin (which the browser rejects).
     */
    public static String allowedOrigin(String allowed, String requestOrigin) {
        if (allowed == null || allowed.isBlank() || allowed.trim().equals("*")) {
            return "*";
        }
        String first = null;
        for (String part : allowed.split(",")) {
            String o = part.trim();
            if (o.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = o;
            }
            if (o.equalsIgnoreCase(requestOrigin)) {
                return requestOrigin;
            }
        }
        return first == null ? "*" : first;
    }

    /** Query string parameter, or null. Values are URL-decoded. */
    public static String query(HttpExchange ex, String name) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
