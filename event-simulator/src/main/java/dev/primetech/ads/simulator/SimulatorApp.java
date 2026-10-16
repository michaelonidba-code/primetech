package dev.primetech.ads.simulator;

import dev.primetech.ads.common.ClientEvent;
import dev.primetech.ads.common.catalog.AdCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;

public final class SimulatorApp {
    private static final Logger log = LoggerFactory.getLogger(SimulatorApp.class);
    private static final long TICK_MS = 20;
    private static final long REPORT_EVERY_MS = 5_000;

    public static void main(String[] args) throws Exception {
        SimulatorConfig config = SimulatorConfig.fromEnv(System.getenv());
        log.info("starting: {}", config);

        var random = new SplittableRandom(config.seed());
        var catalog = AdCatalog.generate(config.campaigns(), random);
        boolean useAdServer = config.adServerUrl() != null && !config.adServerUrl().isBlank();
        // With an ad server, it owns and announces the campaign catalog.
        if (!useAdServer && config.kafkaBootstrap() != null && !config.kafkaBootstrap().isBlank()) {
            CatalogPublisher.publish(config.kafkaBootstrap(), catalog.campaigns());
        }
        var adServer = useAdServer ? new AdServerPodSource(config.adServerUrl(), config.adRequestConcurrency()) : null;
        var truth = new GroundTruth();
        var engine = new TrafficEngine(new SessionPlanner(catalog),
                new FaultInjector(config.duplicateRate(), config.lateRate(), config.invalidRate()),
                random, truth, config.targetEventsPerSecond(), config.lateDelayMs(),
                new TrafficEngine.Bots(config.botSessionShare(), config.botDevices()), adServer);

        try (var client = new GatewayClient(config.gatewayUrl(), config.concurrency())) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> log.info("ground truth: {}", truth.summary())));

            long start = System.currentTimeMillis();
            long end = config.durationSeconds() > 0 ? start + config.durationSeconds() * 1000 : Long.MAX_VALUE;
            long nextReport = start + REPORT_EVERY_MS;
            long sentAtLastReport = 0;

            while (System.currentTimeMillis() < end) {
                long now = System.currentTimeMillis();
                List<ClientEvent> due = engine.tick(now);
                for (int i = 0; i < due.size(); i += config.batchSize()) {
                    client.submit(List.copyOf(due.subList(i, Math.min(due.size(), i + config.batchSize()))));
                }
                if (now >= nextReport) {
                    long sent = client.sentEvents.sum();
                    log.info("rate={}/s accepted={} rejected={} retries={} failed={} inFlight={} activeSessions={} p99={}ms",
                            (sent - sentAtLastReport) * 1000 / REPORT_EVERY_MS, client.accepted.sum(),
                            client.rejected.sum(), client.retries.sum(), client.failedEvents.sum(),
                            client.inFlight(), engine.activeSessions(), client.drainP99Millis());
                    if (adServer != null) {
                        log.info("ad server: {}", adServer.summary());
                    }
                    sentAtLastReport = sent;
                    nextReport += REPORT_EVERY_MS;
                }
                long sleep = TICK_MS - (System.currentTimeMillis() - now);
                if (sleep > 0) {
                    Thread.sleep(sleep);
                }
            }
            // Release held-back late and duplicate copies so the ground truth matches what was sent.
            List<ClientEvent> pending = engine.drainDelayed();
            for (int i = 0; i < pending.size(); i += config.batchSize()) {
                client.submit(List.copyOf(pending.subList(i, Math.min(pending.size(), i + config.batchSize()))));
            }
            client.drain(Duration.ofSeconds(30));
            long elapsed = Math.max(1, (System.currentTimeMillis() - start) / 1000);
            log.info("done: sent={} ({}/s avg) accepted={} rejected={} retries={} failed={}",
                    client.sentEvents.sum(), client.sentEvents.sum() / elapsed, client.accepted.sum(),
                    client.rejected.sum(), client.retries.sum(), client.failedEvents.sum());
            if (adServer != null) {
                log.info("ad server: {}", adServer.summary());
                adServer.close();
            }
        }
    }

    private SimulatorApp() {
    }
}
