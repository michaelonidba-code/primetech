package dev.primetech.ads.simulator;

import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.Topics;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Stands in for the campaign management system until the ad server exists (Phase 3): writes the
 * campaign catalog to the compacted metadata topic, keyed by campaign id. Re-publishing is
 * harmless because compaction keeps the latest value per key.
 */
final class CatalogPublisher {
    private static final Logger log = LoggerFactory.getLogger(CatalogPublisher.class);

    private CatalogPublisher() {
    }

    static void publish(String bootstrapServers, List<CampaignMetadata> campaigns) throws Exception {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.CLIENT_ID_CONFIG, "simulator-catalog",
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        try (var producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer())) {
            for (CampaignMetadata c : campaigns) {
                producer.send(new ProducerRecord<>(Topics.CAMPAIGN_METADATA, c.campaignId(),
                        Json.MAPPER.writeValueAsBytes(c)));
            }
            producer.flush();
            // Surface any failed send rather than starting traffic against missing metadata.
            producer.send(new ProducerRecord<>(Topics.CAMPAIGN_METADATA, campaigns.get(0).campaignId(),
                    Json.MAPPER.writeValueAsBytes(campaigns.get(0)))).get(30, TimeUnit.SECONDS);
            producer.close(Duration.ofSeconds(10));
        }
        log.info("published {} campaigns to {}", campaigns.size(), Topics.CAMPAIGN_METADATA);
    }
}
