package dev.primetech.ads.simulator;

import dev.primetech.ads.common.ClientEvent;
import dev.primetech.ads.common.EventType;
import dev.primetech.ads.common.EventValidator;
import dev.primetech.ads.common.catalog.AdCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TrafficEngineTest {
    private static final long START = 1_760_000_000_000L;

    private record Run(List<ClientEvent> events, GroundTruth truth, long simulatedSeconds) {
    }

    private static Run simulate(double eps, double dup, double late, double invalid, long seconds) {
        return simulate(eps, dup, late, invalid, seconds, TrafficEngine.Bots.NONE, false);
    }

    private static Run simulate(double eps, double dup, double late, double invalid, long seconds,
                                TrafficEngine.Bots bots, boolean drainAtEnd) {
        var random = new SplittableRandom(5);
        var truth = new GroundTruth();
        var engine = new TrafficEngine(new SessionPlanner(AdCatalog.generate(20, random)),
                new FaultInjector(dup, late, invalid), random, truth, eps, 30_000, bots);
        var out = new ArrayList<ClientEvent>();
        for (long t = START; t <= START + seconds * 1000; t += 20) {
            out.addAll(engine.tick(t));
        }
        if (drainAtEnd) {
            out.addAll(engine.drainDelayed());
        }
        return new Run(out, truth, seconds);
    }

    @Test
    void drainingDelayedEventsMakesGroundTruthExact() {
        Run run = simulate(2_000, 0.02, 0.03, 0.01, 60, TrafficEngine.Bots.NONE, true);
        var validator = EventValidator.withDefaults();
        long runEnd = START + run.simulatedSeconds() * 1000;

        long uniqueValid = run.events().stream()
                .filter(e -> validator.validate(e, runEnd).isEmpty())
                .map(ClientEvent::eventId).distinct().count();
        assertThat(uniqueValid).isEqualTo(run.truth().uniqueValidTotal());
    }

    @Test
    void botsReuseAFewDevicesAtHighFrequency() {
        Run run = simulate(2_000, 0, 0, 0, 120, new TrafficEngine.Bots(0.05, 5), true);

        var botEvents = run.events().stream().filter(e -> e.deviceId().startsWith("bot-")).toList();
        assertThat(botEvents).hasSize((int) run.truth().botEvents.sum());
        assertThat(botEvents.stream().map(ClientEvent::deviceId).distinct().count()).isLessThanOrEqualTo(5);
        assertThat(botEvents).allSatisfy(e -> assertThat(e.consent()).isTrue());
        // Each bot device fires far more impressions per minute than any person could.
        long perDevicePerMinute = botEvents.stream().filter(e -> e.type() == EventType.IMPRESSION).count() / 5 / 2;
        assertThat(perDevicePerMinute).isGreaterThan(30);
    }

    @Test
    void playsPodsFromAnAsynchronousAdSource() throws Exception {
        var random = new SplittableRandom(9);
        var truth = new GroundTruth();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        // Every third break goes unfilled; the others get one ad with an auction id, answered on another thread.
        PodSource source = (viewer, onPod) -> executor.execute(() -> onPod.accept(requests.incrementAndGet() % 3 == 0
                ? List.of()
                : List.of(new SessionPlanner.PodAd("ad-x", "cr-x", "camp-x", "li-x", 15, 12_345, "auction-" + viewer.sessionId()))));
        var engine = new TrafficEngine(new SessionPlanner(AdCatalog.generate(5, random)), new FaultInjector(0, 0, 0),
                random, truth, 500, 30_000, TrafficEngine.Bots.NONE, source);

        var events = new ArrayList<ClientEvent>();
        for (long t = START; t <= START + 60_000; t += 20) {
            events.addAll(engine.tick(t));
            if (t % 1_000 == 0) {
                Thread.sleep(1); // let callbacks land
            }
        }
        executor.shutdown();

        assertThat(events).isNotEmpty().allSatisfy(e -> {
            assertThat(e.campaignId()).isEqualTo("camp-x");
            assertThat(e.auctionId()).isEqualTo("auction-" + e.sessionId());
        });
        assertThat(events).filteredOn(e -> e.type() == EventType.IMPRESSION)
                .allSatisfy(e -> assertThat(e.priceMicros()).isEqualTo(12_345L));
        assertThat(truth.noFill.sum()).isPositive();
    }

    @Test
    void hitsTargetRateInSteadyState() {
        // Sessions last up to ~90s, so measure after ramp-up.
        Run run = simulate(5_000, 0, 0, 0, 300);
        long steadyFrom = START + 120_000;
        long steady = run.events().stream().filter(e -> e.eventTimeMs() >= steadyFrom).count();

        assertThat(steady / 180.0).isCloseTo(5_000, within(500.0));
    }

    @Test
    void groundTruthMatchesUniqueValidEventsSent() {
        Run run = simulate(2_000, 0.02, 0.03, 0.01, 120);
        var validator = EventValidator.withDefaults();

        var uniqueValidIds = new HashSet<String>();
        long invalid = 0, duplicates = 0;
        long runEnd = START + run.simulatedSeconds() * 1000;
        for (ClientEvent e : run.events()) {
            // Injected future events are an hour ahead of emission, so still in the future at run end.
            if (validator.validate(e, runEnd).isPresent()) {
                invalid++;
            } else if (!uniqueValidIds.add(e.eventId())) {
                duplicates++;
            }
        }
        // Delayed copies scheduled after the run ended are not in the output yet.
        assertThat(uniqueValidIds.size()).isLessThanOrEqualTo((int) run.truth().uniqueValidTotal())
                .isGreaterThan((int) (run.truth().uniqueValidTotal() * 0.99));
        assertThat(invalid).isEqualTo(run.truth().invalid.sum());
        assertThat(duplicates).isLessThanOrEqualTo(run.truth().duplicates.sum()).isPositive();
    }

    @Test
    void lateEventsArriveAfterNewerEvents() {
        Run run = simulate(1_000, 0, 0.05, 0, 90);
        long maxSeen = 0;
        long outOfOrderBy30s = 0;
        for (ClientEvent e : run.events()) {
            if (e.eventTimeMs() < maxSeen - 25_000) {
                outOfOrderBy30s++;
            }
            maxSeen = Math.max(maxSeen, e.eventTimeMs());
        }
        assertThat(outOfOrderBy30s).isPositive();
    }
}
