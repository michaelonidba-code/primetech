package dev.primetech.ads.stream;

import dev.primetech.ads.common.Json;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.metrics.Counter;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/** JSON (de)serialization for Kafka using the same mapper as the other services. */
final class JsonKafka {
    private JsonKafka() {
    }

    /**
     * Skips records it cannot parse (and tombstones) instead of failing the job: one bad record
     * must not stop the pipeline. Skips are counted so they show up in monitoring.
     */
    static <T> KafkaRecordDeserializationSchema<T> deserializer(Class<T> type) {
        return new KafkaRecordDeserializationSchema<>() {
            private transient Counter malformed;

            @Override
            public void open(DeserializationSchema.InitializationContext context) {
                malformed = context.getMetricGroup().counter("malformedRecords");
            }

            @Override
            public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<T> out) {
                if (record.value() == null) {
                    return;
                }
                try {
                    out.collect(Json.MAPPER.readValue(record.value(), type));
                } catch (IOException e) {
                    malformed.inc();
                }
            }

            @Override
            public TypeInformation<T> getProducedType() {
                return TypeInformation.of(type);
            }
        };
    }

    static <T> KafkaRecordSerializationSchema<T> serializer(String topic, SerializableKey<T> key) {
        return (element, context, timestamp) -> {
            try {
                return new ProducerRecord<>(topic, null, timestamp,
                        key.apply(element).getBytes(StandardCharsets.UTF_8), Json.MAPPER.writeValueAsBytes(element));
            } catch (IOException e) {
                throw new IllegalStateException("cannot serialize " + element, e);
            }
        };
    }

    interface SerializableKey<T> extends Function<T, String>, java.io.Serializable {
    }
}
