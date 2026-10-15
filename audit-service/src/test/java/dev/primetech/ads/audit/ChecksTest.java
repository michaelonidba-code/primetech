package dev.primetech.ads.audit;

import dev.primetech.ads.audit.Checks.Totals;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksTest {

    @Test
    void flinkAndDruidMustAgreeExactly() {
        var flink = Map.of("li-1|0", new Totals(10, 300_000), "li-2|0", new Totals(5, 150_000));
        assertThat(Checks.flinkVsDruid(flink, Map.copyOf(flink), 0, 60_000, 1).status()).isEqualTo(CheckResult.Status.OK);

        // One impression missing from Druid, and a minute Flink never produced.
        var druid = Map.of("li-1|0", new Totals(9, 270_000), "li-2|0", new Totals(5, 150_000), "li-3|0", new Totals(1, 30_000));
        CheckResult r = Checks.flinkVsDruid(flink, druid, 0, 60_000, 1);
        assertThat(r.status()).isEqualTo(CheckResult.Status.MISMATCH);
        assertThat(r.compared()).isEqualTo(3);
        assertThat(r.mismatches()).isEqualTo(2);
        assertThat(r.examples()).anyMatch(e -> e.startsWith("li-1|0: Flink 10 imps"));
    }

    @Test
    void minutesWithNothingBillableOnEitherSideAgree() {
        // Flink emits a minute for quartile events alone; Druid may have no row for it.
        var flink = Map.of("li-1|0", new Totals(0, 0));
        assertThat(Checks.flinkVsDruid(flink, Map.of(), 0, 60_000, 1).status()).isEqualTo(CheckResult.Status.OK);
    }

    @Test
    void servedCountersMustMatchTheDecisionLog() {
        assertThat(Checks.servedVsDecisions(Map.of("a", 5L), Map.of("a", 5L), 0, 1, 1).status())
                .isEqualTo(CheckResult.Status.OK);
        CheckResult r = Checks.servedVsDecisions(Map.of("a", 5L, "b", 7L), Map.of("a", 5L), 0, 1, 1);
        assertThat(r.status()).isEqualTo(CheckResult.Status.MISMATCH);
        assertThat(r.examples()).containsExactly("b: decision log $0.00, Redis $0.00");
    }

    @Test
    void flagsOnlyLineItemsMoreThanOnePercentOverBudget() {
        var budgets = Map.of("a", 100_000_000L, "b", 100_000_000L, "c", 100_000_000L);
        var delivered = Map.of("a", 100_900_000L, "b", 101_100_000L, "c", 50_000_000L);
        CheckResult r = Checks.budgets(budgets, delivered, 0, 1, 1);
        assertThat(r.mismatches()).isEqualTo(1);
        assertThat(r.examples()).containsExactly("b: delivered $101.10 of $100.00 (101.1%)");
    }

    @Test
    void deliveryRatioOutsideTheExpectedRangeIsAMismatch() {
        assertThat(Checks.deliveryRatio(96, 100, 0, 1, 1).status()).isEqualTo(CheckResult.Status.OK);
        assertThat(Checks.deliveryRatio(60, 100, 0, 1, 1).status()).isEqualTo(CheckResult.Status.MISMATCH);
        assertThat(Checks.deliveryRatio(110, 100, 0, 1, 1).status()).isEqualTo(CheckResult.Status.MISMATCH);
        assertThat(Checks.deliveryRatio(0, 0, 0, 1, 1).status()).isEqualTo(CheckResult.Status.PENDING);
    }
}
