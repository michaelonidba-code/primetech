package dev.primetech.ads.simulator;

import java.util.Map;

public record SimulatorConfig(
        String gatewayUrl,
        double targetEventsPerSecond,
        int batchSize,
        int concurrency,
        long durationSeconds,
        int campaigns,
        double duplicateRate,
        double lateRate,
        long lateDelayMs,
        double invalidRate,
        long seed) {

    public static SimulatorConfig fromEnv(Map<String, String> env) {
        return new SimulatorConfig(
                env.getOrDefault("GATEWAY_URL", "http://localhost:8080"),
                Double.parseDouble(env.getOrDefault("TARGET_EPS", "2000")),
                Integer.parseInt(env.getOrDefault("BATCH_SIZE", "200")),
                Integer.parseInt(env.getOrDefault("CONCURRENCY", "16")),
                Long.parseLong(env.getOrDefault("DURATION_SECONDS", "0")),
                Integer.parseInt(env.getOrDefault("CAMPAIGNS", "50")),
                Double.parseDouble(env.getOrDefault("DUPLICATE_RATE", "0.01")),
                Double.parseDouble(env.getOrDefault("LATE_RATE", "0.02")),
                Long.parseLong(env.getOrDefault("LATE_DELAY_MS", "30000")),
                Double.parseDouble(env.getOrDefault("INVALID_RATE", "0.005")),
                Long.parseLong(env.getOrDefault("SEED", "42")));
    }
}
