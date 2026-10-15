package dev.primetech.ads.adserver;

import dev.primetech.ads.common.DeviceIdHasher;
import dev.primetech.ads.common.catalog.AdCatalog;
import dev.primetech.ads.common.catalog.LineItemConfig;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.primetech.ads.common.pacing.PacingPeriod;

import java.time.Duration;
import java.util.ArrayList;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;

public final class AdServerApp {
    private static final Logger log = LoggerFactory.getLogger(AdServerApp.class);

    public static void main(String[] args) throws Exception {
        AdServerConfig config = AdServerConfig.fromEnv(System.getenv());

        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().commonTags("service", "ad-server");
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);

        var http = DspClient.sharedHttpClient();
        var dsps = new ArrayList<DspClient>();
        config.dsps().forEach((name, uri) -> dsps.add(new DspClient(name, uri, http)));
        var metrics = new AdServerMetrics(registry, new ArrayList<>(config.dsps().keySet()));

        FrequencyCaps caps;
        if (config.redisUrl() == null || config.redisUrl().isBlank()) {
            log.warn("REDIS_URL not set: frequency caps are per process");
            caps = new InMemoryFrequencyCaps();
        } else {
            caps = new RedisFrequencyCaps(config.redisUrl());
        }
        var sink = new KafkaDecisionSink(config.kafkaBootstrap(), registry);
        var catalog = AdCatalog.generate(config.campaigns(), new SplittableRandom(config.seed()));
        // Budgets as the pacing service and this server will both see them.
        var lineItems = catalog.lineItems().stream()
                .map(l -> new LineItemConfig(l.lineItemId(), l.campaignId(), l.advertiserId(), l.tier(), l.cpmMicros(),
                        Math.round(l.periodBudgetMicros() * config.budgetScale())))
                .toList();
        PacingView pacing;
        if (config.redisUrl() == null || config.redisUrl().isBlank()) {
            log.warn("REDIS_URL not set: no budgets, direct line items at fixed participation");
            pacing = PacingView.fixed(config.directParticipation());
        } else {
            pacing = new RedisPacingView(config.redisUrl(), PacingPeriod.of(Duration.ofMinutes(config.pacingPeriodMinutes())),
                    lineItems, config.directParticipation(), System::currentTimeMillis, registry);
        }
        if (pacing instanceof RedisPacingView view) {
            metrics.pacingSummary(view::summary);
        }
        var direct = new DirectDemand(catalog, pacing);
        var service = new AdDecisionService(config.settings(), direct, dsps, caps, sink,
                new DeviceIdHasher(config.deviceIdSecret()), metrics);
        // Enrichment depends on the catalog being in Kafka: do not serve until it is.
        service.announce(catalog.campaigns()).get(60, java.util.concurrent.TimeUnit.SECONDS);
        // The pacing service learns budgets from here.
        CompletableFuture.allOf(lineItems.stream().map(sink::lineItem).toArray(CompletableFuture[]::new))
                .get(60, java.util.concurrent.TimeUnit.SECONDS);

        var server = new AdServerHttp(config.port(), service, metrics, registry, config.corsOrigin(),
                config.maxInFlight());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            sink.close();
            if (caps instanceof AutoCloseable c) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // shutting down
                }
            }
        }, "shutdown"));
        server.start();
        log.info("{} direct campaigns, DSPs {}, {}", catalog.campaigns().size(), config.dsps().keySet(), config.settings());
    }

    private AdServerApp() {
    }
}
