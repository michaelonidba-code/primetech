package dev.primetech.ads.gateway;

import dev.primetech.ads.common.DeviceIdHasher;
import dev.primetech.ads.common.EventNormalizer;
import dev.primetech.ads.common.EventValidator;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

public final class GatewayApp {

    public static void main(String[] args) throws Exception {
        GatewayConfig config = GatewayConfig.fromEnv(System.getenv());

        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().commonTags("service", "ingest-gateway");
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        var metrics = new IngestMetrics(registry);
        var sink = new KafkaEventSink(config.kafkaBootstrap(), registry);
        var ingest = new IngestService(
                EventValidator.withDefaults(),
                new EventNormalizer(new DeviceIdHasher(config.deviceIdSecret())),
                sink, metrics, System::currentTimeMillis, config.publishTimeout());
        var server = new GatewayServer(config, ingest, metrics, registry);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Stop taking requests first, then flush whatever the producer still holds.
            server.close();
            sink.close();
        }, "shutdown"));
        server.start();
    }

    private GatewayApp() {
    }
}
