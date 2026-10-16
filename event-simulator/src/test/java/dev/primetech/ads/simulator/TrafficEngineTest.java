package dev.primetech.ads.simulator;

import dev.primetech.ads.common.ClientEvent;
import dev.primetech.ads.common.EventValidator;
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
        var random = new SplittableRandom(5);
        var truth = new GroundTruth();
        var engine = new TrafficEngine(new SessionPlanner(AdCatalog.generate(20, random)),
                new FaultInjector(dup, late, invalid), random, truth, eps, 30_000);
        var out = new ArrayList<ClientEvent>();
        for (long t = START; t <= START + seconds * 1000; t += 20) {
            out.addAll(engine.tick(t));
        }
        return new Run(out, truth, seconds);
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
