package dev.primetech.ads.audit;

import com.fasterxml.jackson.databind.JsonNode;
import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.pacing.ServedSpend;
import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** The two sources the audit queries on demand: Druid (SQL over HTTP) and Redis. */
public interface Sources {

    /** Billable impressions and spend per line item and minute, keyed {@code lineItemId|minuteStartMs}. */
    Map<String, Checks.Totals> druidMinutes(long from, long to) throws IOException;

    /** Billable spend per line item over [from, to). */
    Map<String, Long> druidSpend(long from, long to) throws IOException;

    /** The ad server's served-spend counters for a period. */
    Map<String, Long> redisServed(long periodIndex, Collection<String> lineItemIds);

    final class Live implements Sources, AutoCloseable {
        private final URI druidSql;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final JedisPooled redis;

        public Live(String druidUrl, String redisUrl) {
            this.druidSql = URI.create(druidUrl.replaceAll("/+$", "") + "/druid/v2/sql");
            URI r = URI.create(redisUrl);
            this.redis = new JedisPooled(r.getHost(), r.getPort() > 0 ? r.getPort() : 6379);
        }

        @Override
        public Map<String, Checks.Totals> druidMinutes(long from, long to) throws IOException {
            var out = new HashMap<String, Checks.Totals>();
            for (JsonNode row : sql("""
                    SELECT lineItemId, TIME_FLOOR(__time, 'PT1M') AS minute_start,
                           SUM(events) FILTER (WHERE type = 'IMPRESSION' AND ivt = 0) AS impressions,
                           SUM(priceMicros) FILTER (WHERE ivt = 0) AS spend
                    FROM ad_events
                    WHERE __time >= MILLIS_TO_TIMESTAMP(%d) AND __time < MILLIS_TO_TIMESTAMP(%d)
                      AND lineItemId IS NOT NULL AND lineItemId <> ''
                    GROUP BY 1, 2""".formatted(from, to))) {
                // ("minute" is a reserved word in Druid SQL: the first version failed with 400.)
                long minute = Instant.parse(row.path("minute_start").asText()).toEpochMilli();
                out.put(row.path("lineItemId").asText() + "|" + minute,
                        new Checks.Totals(row.path("impressions").asLong(), row.path("spend").asLong()));
            }
            return out;
        }

        @Override
        public Map<String, Long> druidSpend(long from, long to) throws IOException {
            var out = new HashMap<String, Long>();
            for (JsonNode row : sql("""
                    SELECT lineItemId, SUM(priceMicros) FILTER (WHERE ivt = 0) AS spend
                    FROM ad_events
                    WHERE __time >= MILLIS_TO_TIMESTAMP(%d) AND __time < MILLIS_TO_TIMESTAMP(%d)
                      AND lineItemId IS NOT NULL AND lineItemId <> ''
                    GROUP BY 1""".formatted(from, to))) {
                out.put(row.path("lineItemId").asText(), row.path("spend").asLong());
            }
            return out;
        }

        @Override
        public Map<String, Long> redisServed(long periodIndex, Collection<String> lineItemIds) {
            return ServedSpend.read(redis, periodIndex, lineItemIds);
        }

        private JsonNode sql(String query) throws IOException {
            var body = Json.MAPPER.writeValueAsBytes(Map.of("query", query));
            var request = HttpRequest.newBuilder(druidSql).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IOException("Druid SQL returned " + response.statusCode());
                }
                return Json.MAPPER.readTree(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }

        @Override
        public void close() {
            redis.close();
        }
    }
}
