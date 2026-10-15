package dev.primetech.ads.dsp;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DspApp {
    private static final Logger log = LoggerFactory.getLogger(DspApp.class);

    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        String presetName = env.getOrDefault("DSP_PROFILE", "budget");
        DspProfile profile = DspProfile.PRESETS.get(presetName);
        if (profile == null) {
            throw new IllegalArgumentException("DSP_PROFILE must be one of " + DspProfile.PRESETS.keySet());
        }
        int port = Integer.parseInt(env.getOrDefault("PORT", "9000"));
        // Where the ad server should send win notices: this DSP's address as the ad server sees it.
        String publicUrl = env.getOrDefault("PUBLIC_URL", "http://localhost:" + port);

        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().commonTags("service", "mock-dsp", "dsp", profile.name());
        var server = new DspServer(port, new Bidder(profile, publicUrl), registry,
                Long.parseLong(env.getOrDefault("SEED", String.valueOf(presetName.hashCode()))), true);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "shutdown"));
        server.start();
        log.info("{} listening on :{} ({})", profile.name(), port, profile);
    }

    private DspApp() {
    }
}
