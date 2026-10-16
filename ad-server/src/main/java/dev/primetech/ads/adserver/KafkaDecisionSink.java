package dev.primetech.ads.adserver;

import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.Topics;
import dev.primetech.ads.common.adserving.AdDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes the decision log and campaign metadata to Kafka without waiting for acks: the ad request
 * must not pay for an audit write.
 *
 * <p>{@code max.block.ms} is small but not zero, and topic metadata is fetched before the first
 * request. With zero, every send fails until the producer has learned the topic's partitions,
 * which silently dropped the whole campaign catalog at start-up in testing.
 */
public final class KafkaDecisionSink implements DecisionSink {
    private static final Logger log = LoggerFactory.getLogger(KafkaDecisionSink.class);

    private final KafkaProducer<String, byte[]> producer;
    private final Counter failures;
    private final AtomicBoolean warnedOnce = new AtomicBoolean();

    public KafkaDecisionSink(String bootstrap, MeterRegistry registry) {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.CLIENT_ID_CONFIG, "ad-server",
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.LINGER_MS_CONFIG, 20,
                ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4",
                // Stall a request by 50 ms at most: if the buffer stays full, drop the record and count it.
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 50),
                new StringSerializer(), new ByteArraySerializer());
        failures = registry.counter("adserver.log.failures");
        warmUp(Topics.AD_DECISIONS, Duration.ofSeconds(60));
        warmUp(Topics.CAMPAIGN_METADATA, Duration.ofSeconds(60));
    }

    /** Fetches a topic's partitions before serving, retrying while the broker comes up. */
    private void warmUp(String topic, Duration patience) {
        long deadline = System.nanoTime() + patience.toNanos();
        while (true) {
            try {
                producer.partitionsFor(topic);
                return;
            } catch (RuntimeException e) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("no metadata for " + topic + " after " + patience, e);
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                }
            }
        }
    }

    @Override
    public void decision(AdDecision decision) {
        send(Topics.AD_DECISIONS, decision.requestId(), decision);
    }

    @Override
    public CompletableFuture<Void> campaign(CampaignMetadata campaign) {
        return send(Topics.CAMPAIGN_METADATA, campaign.campaignId(), campaign);
    }

    private CompletableFuture<Void> send(String topic, String key, Object value) {
        var result = new CompletableFuture<Void>();
        try {
            producer.send(new ProducerRecord<>(topic, key, Json.MAPPER.writeValueAsBytes(value)), (m, e) -> {
                if (e != null) {
                    failed(topic, e);
                    result.completeExceptionally(e);
                } else {
                    result.complete(null);
                }
            });
        } catch (IOException | RuntimeException e) {
            failed(topic, e);
            result.completeExceptionally(e);
        }
        return result;
    }

    private void failed(String topic, Exception e) {
        failures.increment();
        if (warnedOnce.compareAndSet(false, true)) {
            log.warn("write to {} failed (further failures are only counted): {}", topic, e.toString());
        }
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(10));
    }
}
