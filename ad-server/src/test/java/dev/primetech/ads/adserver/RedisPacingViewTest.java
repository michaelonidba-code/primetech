package dev.primetech.ads.adserver;

import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.pacing.PacingPeriod;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisPacingViewTest {
    @Test
    void withoutPacingStateFallsBackToASmallThrottleAndStillStopsAtTheBudget() {
        var li = new LineItemConfig("li-1", "camp", "adv", LineItemConfig.Tier.STANDARD, 30_000_000, 8_000_000);
        // Nothing listens on port 1: every Redis call fails, as when Redis is down.
        try (var view = new RedisPacingView("redis://localhost:1", PacingPeriod.of(Duration.ofMinutes(30)), List.of(li),
                0.01, System::currentTimeMillis, new SimpleMeterRegistry())) {
            assertThat(view.throttle("li-1")).isEqualTo(0.01);
            assertThat(view.throttle("unknown")).isZero();

            // Without pacing state the stop is the budget itself in served spend ($8): delivery
            // never exceeds what was served, so this cannot overspend.
            view.served("li-1", 7_990_000);
            assertThat(view.throttle("li-1")).isEqualTo(0.01);
            view.served("li-1", 10_000);
            assertThat(view.throttle("li-1")).isZero();
        }
    }
}
