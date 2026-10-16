package dev.primetech.ads.pacing;

import com.sun.net.httpserver.HttpServer;
import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.LineItemMinute;
import dev.primetech.ads.common.Topics;
import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.http.JsonHttp;
import dev.primetech.ads.common.pacing.PacingPeriod;
import dev.primetech.ads.common.pacing.PacingState;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Wires {@link PacingService} to Kafka (inputs), Redis (outputs) and HTTP (stats). State is rebuilt
 * from the topics on every start: both are compacted or short-lived, and the current period's
 * minutes are all it needs, so there is nothing to persist.
 */
public final class PacingApp {
    private static final Logger log = LoggerFactory.getLogger(PacingApp.class);

    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        String bootstrap = env.getOrDefault("KAFKA_BOOTSTRAP", "localhost:19092");
        String redisUrl = env.getOrDefault("REDIS_URL", "redis://localhost:6379");
        var period = PacingPeriod.of(Duration.ofMinutes(Long.parseLong(env.getOrDefault("PACING_PERIOD_MINUTES", "30"))));
        long tickSeconds = Long.parseLong(env.getOrDefault("PACING_TICK_SECONDS", "10"));
        int port = Integer.parseInt(env.getOrDefault("PORT", "8095"));

        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().commonTags("service", "pacing-service");
        var store = new RedisPacingStore(redisUrl);
        var service = new PacingService(period, store, System::currentTimeMillis, registry);

        Thread consumer = Thread.ofPlatform().name("pacing-consumer").daemon().start(() -> consume(bootstrap, service));
        var ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(() -> {
            try {
                service.tick();
            } catch (RuntimeException e) {
                log.error("pacing tick failed", e);
            }
        }, tickSeconds, tickSeconds, TimeUnit.SECONDS);

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 64);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/stats", ex -> {
            try (ex) {
                JsonHttp.cors(ex, "*");
                JsonHttp.json(ex, 200, stats(service, period));
            }
        });
        http.createContext("/metrics", ex -> {
            try (ex) {
                JsonHttp.send(ex, 200, "text/plain; version=0.0.4", registry.scrape().getBytes(StandardCharsets.UTF_8));
            }
        });
        http.createContext("/health", ex -> {
            try (ex) {
                JsonHttp.json(ex, 200, Map.of("status", consumer.isAlive() ? "UP" : "DOWN"));
            }
        });
        http.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop(1);
            ticker.shutdownNow();
            store.close();
        }, "shutdown"));
        log.info("pacing service on :{}, period {} min, tick {} s", port, period.lengthMs() / 60_000, tickSeconds);
    }

    /** Reads both topics from the beginning, forever. No consumer group: every instance sees everything. */
    private static void consume(String bootstrap, PacingService service) {
        var config = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                // Minute totals are written transactionally: only count committed ones.
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.CLIENT_ID_CONFIG, "pacing-service");
        while (true) {
            try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
                List<TopicPartition> partitions = new ArrayList<>();
                for (String topic : List.of(Topics.LINE_ITEMS, Topics.LINE_ITEM_MINUTES)) {
                    consumer.partitionsFor(topic, Duration.ofSeconds(30))
                            .forEach(p -> partitions.add(new TopicPartition(topic, p.partition())));
                }
                consumer.assign(partitions);
                consumer.seekToBeginning(partitions);
                while (true) {
                    for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                        if (r.value() == null) {
                            continue;
                        }
                        try {
                            if (r.topic().equals(Topics.LINE_ITEMS)) {
                                service.lineItem(Json.MAPPER.readValue(r.value(), LineItemConfig.class));
                            } else {
                                service.minute(Json.MAPPER.readValue(r.value(), LineItemMinute.class));
                            }
                        } catch (IOException e) {
                            log.warn("skipping malformed record on {}: {}", r.topic(), e.toString());
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn("consumer failed, restarting in 5 s: {}", e.toString());
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static Map<String, Object> stats(PacingService service, PacingPeriod period) {
        long now = System.currentTimeMillis();
        long index = period.index(now);
        var out = new LinkedHashMap<String, Object>();
        out.put("periodIndex", index);
        out.put("periodStartMs", period.start(index));
        out.put("periodEndMs", period.end(index));
        out.put("elapsed", period.elapsed(now));
        out.put("horizonMs", service.horizonMs());
        var items = new ArrayList<Map<String, Object>>();
        for (PacingState s : service.latest()) {
            LineItemConfig c = service.config(s.lineItemId());
            var m = new LinkedHashMap<String, Object>();
            m.put("lineItemId", s.lineItemId());
            m.put("campaignId", c == null ? null : c.campaignId());
            m.put("tier", c == null ? null : c.tier());
            m.put("budgetMicros", s.budgetMicros());
            m.put("deliveredMicros", s.deliveredMicros());
            m.put("targetMicros", s.targetMicros());
            m.put("throttle", s.throttle());
            m.put("status", s.status());
            items.add(m);
        }
        out.put("lineItems", items);
        return out;
    }

    private PacingApp() {
    }
}
