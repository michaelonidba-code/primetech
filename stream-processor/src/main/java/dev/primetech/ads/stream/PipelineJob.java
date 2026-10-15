package dev.primetech.ads.stream;

import dev.primetech.ads.common.AdEvent;
import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.EnrichedAdEvent;
import dev.primetech.ads.common.LineItemMinute;
import dev.primetech.ads.common.Topics;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.core.execution.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

import java.util.Properties;

/** Wires {@link AdPipeline} to Kafka with exactly-once output. */
public final class PipelineJob {

    public static void main(String[] args) throws Exception {
        PipelineConfig config = PipelineConfig.fromEnv(System.getenv());
        var env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(config.checkpointInterval().toMillis(), CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(config.checkpointInterval().toMillis() / 2);

        KafkaSource<AdEvent> eventSource = KafkaSource.<AdEvent>builder()
                .setBootstrapServers(config.kafkaBootstrap())
                .setTopics(Topics.AD_EVENTS)
                .setGroupId(config.consumerGroup())
                // A checkpoint, when there is one, decides the offsets. Without one (e.g. the job manager
                // restarted without HA), continue from the offsets committed at the last checkpoint
                // rather than replaying the topic against empty dedup state. On a very first start,
                // process everything already in the topic.
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
                .setDeserializer(JsonKafka.deserializer(AdEvent.class))
                .build();
        DataStream<AdEvent> events = env.fromSource(eventSource,
                WatermarkStrategy.<AdEvent>forBoundedOutOfOrderness(config.outOfOrderness())
                        .withTimestampAssigner((e, ts) -> e.eventTimeMs())
                        .withIdleness(config.sourceIdleness()),
                "ad-events").uid("ad-events-source");

        KafkaSource<CampaignMetadata> metadataSource = KafkaSource.<CampaignMetadata>builder()
                .setBootstrapServers(config.kafkaBootstrap())
                .setTopics(Topics.CAMPAIGN_METADATA)
                .setGroupId(config.consumerGroup() + "-metadata")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setDeserializer(JsonKafka.deserializer(CampaignMetadata.class))
                .build();
        // The metadata topic is quiet most of the time. Marking it idle keeps it from holding back
        // the watermark of the enrichment operator, which is the minimum of both inputs.
        DataStream<CampaignMetadata> metadata = env.fromSource(metadataSource,
                WatermarkStrategy.<CampaignMetadata>noWatermarks().withIdleness(config.sourceIdleness()),
                "campaign-metadata").uid("campaign-metadata-source");

        AdPipeline.Outputs out = AdPipeline.build(events, metadata, config);

        out.cleanEvents().sinkTo(transactionalSink(config, Topics.AD_EVENTS_CLEAN, "ads-clean",
                JsonKafka.<EnrichedAdEvent>serializer(Topics.AD_EVENTS_CLEAN, EnrichedAdEvent::sessionId)))
                .name("clean-events-sink").uid("clean-events-sink");
        out.lineItemMinutes().sinkTo(transactionalSink(config, Topics.LINE_ITEM_MINUTES, "ads-minutes",
                JsonKafka.<LineItemMinute>serializer(Topics.LINE_ITEM_MINUTES, LineItemMinute::key)))
                .name("line-item-minutes-sink").uid("line-item-minutes-sink");
        // Counted by the window operator's numLateRecordsDropped metric; nothing else to do with them.
        out.tooLate().sinkTo(new DiscardingSink<>()).name("too-late-for-totals").uid("too-late-sink");

        env.execute("ads-eventing-pipeline");
    }

    /**
     * Writes inside Kafka transactions committed on each checkpoint, so consumers reading with
     * {@code isolation.level=read_committed} see every record exactly once. The cost is latency:
     * output becomes visible only after the next checkpoint completes.
     */
    private static <T> KafkaSink<T> transactionalSink(PipelineConfig config, String topic, String txPrefix,
                                                      org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema<T> schema) {
        var props = new Properties();
        // Must not exceed the broker's maximum (Redpanda: transaction_max_timeout_ms, 15 min by default).
        props.setProperty("transaction.timeout.ms", "600000");
        props.setProperty("compression.type", "lz4");
        return KafkaSink.<T>builder()
                .setBootstrapServers(config.kafkaBootstrap())
                .setRecordSerializer(schema)
                .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
                .setTransactionalIdPrefix(txPrefix)
                .setKafkaProducerConfig(props)
                .build();
    }

    private PipelineJob() {
    }
}
