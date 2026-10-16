package dev.primetech.ads.pacing;

import dev.primetech.ads.common.LineItemMinute;
import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.pacing.PacingPeriod;
import dev.primetech.ads.common.pacing.PacingState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PacingServiceTest {
    private static final PacingPeriod PERIOD = PacingPeriod.of(Duration.ofMinutes(30));
    private static final long T0 = PERIOD.start(1_000_000);

    static final class FakeStore implements PacingStore {
        final Map<String, Long> served = new HashMap<>();
        List<PacingState> published = List.of();
        boolean failing;

        @Override
        public Map<String, Long> served(long periodIndex, Collection<String> ids) {
            if (failing) {
                throw new IllegalStateException("redis down");
            }
            return served;
        }

        @Override
        public void publish(List<PacingState> states) {
            published = states;
        }
    }

    private final FakeStore store = new FakeStore();
    private final AtomicLong now = new AtomicLong(T0 + 10 * 60_000);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PacingService service = new PacingService(PERIOD, store, now::get, registry);

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    private static LineItemConfig config(String id, long budget) {
        return new LineItemConfig(id, "camp", "adv", LineItemConfig.Tier.STANDARD, 30_000_000, budget);
    }

    private static LineItemMinute minute(String li, long start, int revision, long spend) {
        return new LineItemMinute(li, "camp", "adv", start, start + 60_000, revision, 1, 0, 0, 0, 0, 0, spend, 0, start);
    }

    @Test
    void countsMinutesThatArriveBeforeTheirConfig() {
        service.minute(minute("li-1", T0, 1, 4_000_000));
        service.minute(minute("li-1", T0 + 60_000, 1, 6_000_000));
        service.lineItem(config("li-1", 90_000_000));

        PacingState s = service.tick().get(0);
        assertThat(s.deliveredMicros()).isEqualTo(10_000_000);
        assertThat(store.published).containsExactly(s);
    }

    @Test
    void keepsOnlyTheLatestRevisionOfEachMinute() {
        service.lineItem(config("li-1", 90_000_000));
        service.minute(minute("li-1", T0, 2, 7_000_000));
        service.minute(minute("li-1", T0, 1, 5_000_000)); // older revision arriving late

        assertThat(service.tick().get(0).deliveredMicros()).isEqualTo(7_000_000);
    }

    @Test
    void aChangedBudgetKeepsDeliverySoFar() {
        service.lineItem(config("li-1", 90_000_000));
        service.minute(minute("li-1", T0, 1, 9_000_000));
        service.lineItem(config("li-1", 10_000_000));

        PacingState s = service.tick().get(0);
        assertThat(s.budgetMicros()).isEqualTo(10_000_000);
        assertThat(s.deliveredMicros()).isEqualTo(9_000_000);
    }

    @Test
    void aStoreOutageKeepsTheLastDecisions() {
        service.lineItem(config("li-1", 90_000_000));
        List<PacingState> first = service.tick();
        store.failing = true;

        assertThat(service.tick()).isEqualTo(first);
    }

    @Test
    void exposesHealthAndOverspendAsMetrics() {
        service.lineItem(config("li-ok", 90_000_000));
        service.lineItem(config("li-over", 10_000_000));
        // Five minutes delivered by the horizon: li-over is at 120% of its budget.
        service.minute(minute("li-ok", T0, 1, 10_000_000));
        service.minute(minute("li-over", T0 + 4 * 60_000, 1, 12_000_000));
        service.tick();

        assertThat(gauge("pacing.overspent.line.items")).isEqualTo(1);
        assertThat(gauge("pacing.delivered.budget.ratio.max")).isEqualTo(1.2);
        assertThat(gauge("pacing.line.items.by.status", "status", "EXHAUSTED")).isEqualTo(1);
        assertThat(gauge("pacing.delivery.lag.seconds")).isEqualTo(5 * 60);
        assertThat(gauge("pacing.publish.age.seconds")).isZero();

        // Redis down for two minutes: state stops reaching the ad server, and the gauge shows it.
        store.failing = true;
        now.addAndGet(120_000);
        service.tick();
        assertThat(gauge("pacing.publish.age.seconds")).isEqualTo(120);
    }
}
