package dev.primetech.ads.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.primetech.ads.common.AdEvent;
import dev.primetech.ads.common.DeadLetter;
import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class KafkaEventSink implements EventSink {
    private final Producer<String, byte[]> producer;
    private final KafkaClientMetrics clientMetrics;

    public KafkaEventSink(String bootstrapServers, MeterRegistry registry) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.CLIENT_ID_CONFIG, "ingest-gateway",
                // Durability: an event is only acknowledged to the player once all in-sync replicas have it.
                ProducerConfig.ACKS_CONFIG, "all",
                // Idempotence stops producer-level retries from duplicating records within a partition.
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                // Throughput: small linger plus large batches; lz4 is cheap on CPU and compresses JSON well.
                ProducerConfig.LINGER_MS_CONFIG, 5,
                ProducerConfig.BATCH_SIZE_CONFIG, 128 * 1024,
                ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4",
                // Fail fast so the HTTP request can return 503 and the client retries.
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        this.producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
        this.clientMetrics = new KafkaClientMetrics(producer);
        clientMetrics.bindTo(registry);
    }

    @Override
    public CompletableFuture<Void> publish(AdEvent event) {
        // Keyed by session so every event of a playback lands on one partition, in order.
        return send(Topics.AD_EVENTS, event.sessionId(), event);
    }

    @Override
    public CompletableFuture<Void> publishDeadLetter(DeadLetter deadLetter) {
        return send(Topics.AD_EVENTS_DLQ, deadLetter.event().eventId(), deadLetter);
    }

    private CompletableFuture<Void> send(String topic, String key, Object value) {
        var result = new CompletableFuture<Void>();
        try {
            byte[] bytes = Json.MAPPER.writeValueAsBytes(value);
            producer.send(new ProducerRecord<>(topic, key, bytes), (metadata, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(null);
                }
            });
        } catch (JsonProcessingException | RuntimeException ex) {
            // send() itself throws when metadata is unavailable or the buffer stays full past max.block.ms.
            result.completeExceptionally(ex);
        }
        return result;
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(10));
        clientMetrics.close();
    }
}
