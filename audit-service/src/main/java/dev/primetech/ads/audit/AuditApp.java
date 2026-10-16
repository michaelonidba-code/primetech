package dev.primetech.ads.audit;

import com.sun.net.httpserver.HttpServer;
import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.LineItemMinute;
import dev.primetech.ads.common.Topics;
import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.http.JsonHttp;
import dev.primetech.ads.common.pacing.PacingPeriod;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wires the audit to Kafka (decision log, minute totals and budgets in; results out), Druid, Redis
 * and HTTP. Like the pacing service it rebuilds its state from the topics on start.
 */
public final class AuditApp {
    private static final Logger log = LoggerFactory.getLogger(AuditApp.class);

    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        String bootstrap = env.getOrDefault("KAFKA_BOOTSTRAP", "localhost:19092");
        var period = PacingPeriod.of(Duration.ofMinutes(Long.parseLong(env.getOrDefault("PACING_PERIOD_MINUTES", "30"))));
        long intervalSeconds = Long.parseLong(env.getOrDefault("AUDIT_INTERVAL_SECONDS", "60"));
        int port = Integer.parseInt(env.getOrDefault("PORT", "8096"));

        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().commonTags("service", "audit-service");
        var ledger = new AuditLedger(period);
        var caughtUp = new AtomicBoolean(false);
        var sources = new Sources.Live(env.getOrDefault("DRUID_URL", "http://localhost:8888"),
                env.getOrDefault("REDIS_URL", "redis://localhost:6379"));
        var producer = new KafkaProducer<>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.CLIENT_ID_CONFIG, "audit-service"), new StringSerializer(), new ByteArraySerializer());
        var service = new AuditService(ledger, sources, period, System::currentTimeMillis, caughtUp::get, r -> {
            try {
                producer.send(new ProducerRecord<>(Topics.AUDIT_RESULTS, r.check(), Json.MAPPER.writeValueAsBytes(r)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, registry);

        Thread.ofPlatform().name("audit-consumer").daemon().start(() -> consume(bootstrap, ledger, caughtUp));
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                service.run();
            } catch (RuntimeException e) {
                log.error("audit round failed", e);
            }
        }, 15, intervalSeconds, TimeUnit.SECONDS);

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 64);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/stats", ex -> {
            try (ex) {
                JsonHttp.json(ex, 200, Map.of("caughtUp", caughtUp.get(), "periodMinutes", period.lengthMs() / 60_000,
                        "checks", service.stats()));
            }
        });
        http.createContext("/metrics", ex -> {
            try (ex) {
                JsonHttp.send(ex, 200, "text/plain; version=0.0.4", registry.scrape().getBytes(StandardCharsets.UTF_8));
            }
        });
        http.createContext("/health", ex -> {
            try (ex) {
                JsonHttp.json(ex, 200, Map.of("status", "UP", "caughtUp", caughtUp.get()));
            }
        });
        http.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop(1);
            scheduler.shutdownNow();
            producer.close(Duration.ofSeconds(5));
            sources.close();
        }, "shutdown"));
        log.info("audit service on :{}, every {} s, period {} min", port, intervalSeconds, period.lengthMs() / 60_000);
    }

    /**
     * Reads the three topics from the beginning, forever, with no consumer group. Marks the ledger
     * caught up once it has read every partition up to where it ended at startup.
     */
    private static void consume(String bootstrap, AuditLedger ledger, AtomicBoolean caughtUp) {
        var config = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5_000,
                ConsumerConfig.CLIENT_ID_CONFIG, "audit-service");
        while (true) {
            try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
                List<TopicPartition> partitions = new ArrayList<>();
                for (String topic : List.of(Topics.LINE_ITEMS, Topics.LINE_ITEM_MINUTES, Topics.AD_DECISIONS)) {
                    consumer.partitionsFor(topic, Duration.ofSeconds(30))
                            .forEach(p -> partitions.add(new TopicPartition(topic, p.partition())));
                }
                consumer.assign(partitions);
                consumer.seekToBeginning(partitions);
                // Re-reading the decision log into a ledger that already counted it would double
                // every line item's spend: start clean, and report pending until caught up again.
                caughtUp.set(false);
                ledger.reset();
                Map<TopicPartition, Long> endAtStart = consumer.endOffsets(partitions);
                while (true) {
                    for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                        if (r.value() == null) {
                            continue;
                        }
                        try {
                            switch (r.topic()) {
                                case Topics.LINE_ITEMS -> ledger.lineItem(Json.MAPPER.readValue(r.value(), LineItemConfig.class));
                                case Topics.LINE_ITEM_MINUTES -> ledger.minute(Json.MAPPER.readValue(r.value(), LineItemMinute.class));
                                default -> ledger.decision(Json.MAPPER.readTree(r.value()));
                            }
                        } catch (IOException e) {
                            log.warn("skipping malformed record on {}: {}", r.topic(), e.toString());
                        }
                    }
                    if (!caughtUp.get() && endAtStart.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue())) {
                        caughtUp.set(true);
                        log.info("caught up with the topics");
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

    private AuditApp() {
    }
}
