package dev.primetech.ads.gateway;

import java.time.Duration;
import java.util.Map;

public record GatewayConfig(
        int port,
        String kafkaBootstrap,
        String deviceIdSecret,
        int maxEventsPerBatch,
        int maxBodyBytes,
        Duration publishTimeout,
        String corsOrigin) {

    public static GatewayConfig fromEnv(Map<String, String> env) {
        return new GatewayConfig(
                Integer.parseInt(env.getOrDefault("PORT", "8080")),
                env.getOrDefault("KAFKA_BOOTSTRAP", "localhost:19092"),
                required(env, "DEVICE_ID_SECRET"),
                Integer.parseInt(env.getOrDefault("MAX_EVENTS_PER_BATCH", "1000")),
                Integer.parseInt(env.getOrDefault("MAX_BODY_BYTES", String.valueOf(1024 * 1024))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("PUBLISH_TIMEOUT_MS", "12000"))),
                env.getOrDefault("CORS_ORIGIN", "*"));
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " must be set");
        }
        return value;
    }
}
