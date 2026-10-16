package dev.primetech.ads.stream;

import java.io.Serializable;
import java.time.Duration;
import java.util.Map;

/** Job settings, read from the environment so the same jar runs locally and in containers. */
public record PipelineConfig(
        String kafkaBootstrap,
        String consumerGroup,
        Duration checkpointInterval,
        Duration outOfOrderness,
        Duration sourceIdleness,
        Duration dedupTtl,
        Duration metadataWait,
        int ivtMaxImpressionsPerDeviceMinute,
        Duration allowedLateness,
        Duration lateFiringDelay) implements Serializable {

    public static PipelineConfig fromEnv(Map<String, String> env) {
        return new PipelineConfig(
                env.getOrDefault("KAFKA_BOOTSTRAP", "localhost:19092"),
                env.getOrDefault("CONSUMER_GROUP", "stream-processor"),
                seconds(env, "CHECKPOINT_INTERVAL_SECONDS", 10),
                seconds(env, "OUT_OF_ORDERNESS_SECONDS", 5),
                seconds(env, "SOURCE_IDLENESS_SECONDS", 30),
                // Long enough to cover client retries; every extra minute costs state (one entry per event).
                Duration.ofMinutes(Long.parseLong(env.getOrDefault("DEDUP_TTL_MINUTES", "30"))),
                seconds(env, "METADATA_WAIT_SECONDS", 60),
                Integer.parseInt(env.getOrDefault("IVT_MAX_IMPRESSIONS_PER_DEVICE_MINUTE", "20")),
                Duration.ofMinutes(Long.parseLong(env.getOrDefault("ALLOWED_LATENESS_MINUTES", "10"))),
                seconds(env, "LATE_FIRING_DELAY_SECONDS", 5));
    }

    /** Settings for tests: small, deterministic values. */
    public static PipelineConfig forTests() {
        return new PipelineConfig("unused", "test", Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofMinutes(30), Duration.ofSeconds(60), 20, Duration.ofMinutes(10),
                // Fire on every late event: bounded test jobs end before processing-time timers would fire.
                Duration.ZERO);
    }

    private static Duration seconds(Map<String, String> env, String key, long def) {
        return Duration.ofSeconds(Long.parseLong(env.getOrDefault(key, String.valueOf(def))));
    }
}
