package dev.primetech.ads.adserver;

import dev.primetech.ads.openrtb.BidRequest;
import dev.primetech.ads.openrtb.OpenRtb;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public record AdServerConfig(
        int port,
        String kafkaBootstrap,
        String redisUrl,
        String deviceIdSecret,
        Map<String, URI> dsps,
        AdDecisionService.Settings settings,
        int campaigns,
        double directParticipation,
        int maxInFlight,
        long seed,
        String corsOrigin) {

    public static AdServerConfig fromEnv(Map<String, String> env) {
        String auction = env.getOrDefault("AUCTION_TYPE", "first");
        if (!auction.equals("first") && !auction.equals("second")) {
            throw new IllegalArgumentException("AUCTION_TYPE must be first or second");
        }
        String secret = env.get("DEVICE_ID_SECRET");
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("DEVICE_ID_SECRET must be set (the same value as the gateway's)");
        }
        return new AdServerConfig(
                Integer.parseInt(env.getOrDefault("PORT", "8090")),
                env.getOrDefault("KAFKA_BOOTSTRAP", "localhost:19092"),
                // Unset runs with in-process caps: fine for one instance, wrong for several.
                env.get("REDIS_URL"),
                secret,
                parseDsps(env.getOrDefault("DSP_ENDPOINTS", "")),
                new AdDecisionService.Settings(
                        Integer.parseInt(env.getOrDefault("MAX_SLOTS", "5")),
                        Duration.ofMillis(Long.parseLong(env.getOrDefault("TMAX_MS", "120"))),
                        OpenRtb.cpmToMicros(Double.parseDouble(env.getOrDefault("FLOOR_CPM", "5.00"))),
                        auction.equals("first") ? BidRequest.FIRST_PRICE : BidRequest.SECOND_PRICE,
                        Integer.parseInt(env.getOrDefault("DSP_FREQUENCY_CAP_PER_HOUR", "4"))),
                Integer.parseInt(env.getOrDefault("CAMPAIGNS", "50")),
                Double.parseDouble(env.getOrDefault("DIRECT_PARTICIPATION", "0.015")),
                Integer.parseInt(env.getOrDefault("MAX_IN_FLIGHT", "256")),
                Long.parseLong(env.getOrDefault("SEED", "42")),
                env.getOrDefault("CORS_ORIGIN", "*"));
    }

    /** {@code name=url,name=url}, e.g. {@code premium-dsp=http://dsp-premium:9000/openrtb2/bid}. */
    static Map<String, URI> parseDsps(String value) {
        var out = new LinkedHashMap<String, URI>();
        for (String entry : value.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("DSP_ENDPOINTS entries must be name=url: " + entry);
            }
            out.put(entry.substring(0, eq).trim(), URI.create(entry.substring(eq + 1).trim()));
        }
        return out;
    }
}
