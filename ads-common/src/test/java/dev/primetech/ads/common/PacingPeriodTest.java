package dev.primetech.ads.common;

import dev.primetech.ads.common.catalog.AdCatalog;
import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.pacing.PacingPeriod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PacingPeriodTest {
    @Test
    void periodsAreEpochAligned() {
        var period = PacingPeriod.of(Duration.ofMinutes(30));
        long t = 1_800_000L * 1000 + 900_000;

        assertThat(period.index(t)).isEqualTo(1000);
        assertThat(period.start(1000)).isEqualTo(1_800_000L * 1000);
        assertThat(period.end(1000)).isEqualTo(1_800_000L * 1001);
        assertThat(period.elapsed(t)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void catalogGivesEveryLineItemATierAndBudget() {
        var catalog = AdCatalog.generate(20, new SplittableRandom(1));

        assertThat(catalog.lineItems()).isNotEmpty()
                .extracting(LineItemConfig::lineItemId).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(catalog.ads().stream().map(AdCatalog.AdSpec::lineItemId).distinct().toList());
        assertThat(catalog.lineItems()).filteredOn(l -> l.tier() == LineItemConfig.Tier.GUARANTEED)
                .isNotEmpty()
                .allSatisfy(l -> assertThat(l.periodBudgetMicros()).isBetween(150_000_000L, 300_000_000L));
        assertThat(catalog.lineItems()).filteredOn(l -> l.tier() == LineItemConfig.Tier.STANDARD)
                .allSatisfy(l -> assertThat(l.periodBudgetMicros()).isBetween(20_000_000L, 80_000_000L));
    }
}
