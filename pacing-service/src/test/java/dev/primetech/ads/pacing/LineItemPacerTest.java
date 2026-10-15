package dev.primetech.ads.pacing;

import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.pacing.PacingPeriod;
import dev.primetech.ads.common.pacing.PacingState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.LongToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closed-loop tests: the pacer drives a simulated market and must hit its budget. The market
 * mimics the real system's delays: spend is reported per event-time minute, about 70 s after the
 * minute starts, ads play up to 30 s after they are served, and only 80% of served spend
 * becomes delivered spend.
 */
class LineItemPacerTest {
    private static final long MINUTE = 60_000;
    private static final PacingPeriod PERIOD = PacingPeriod.of(Duration.ofMinutes(30));
    private static final long T0 = PERIOD.start(1_000_000);
    private static final double DELIVERY_RATIO = 0.8;
    private static final long REPORT_LAG_MS = 70_000;
    /** Served ads play between 0 and this long later (15 s on average, as measured end to end). */
    private static final long MAX_PLAY_DELAY_MS = 30_000;

    record Run(List<PacingState> states, long finalDelivered, long budget, double maxTrackingError) {
    }

    /**
     * @param yieldPerMinute delivered spend per minute the line item would get at throttle 1, over time
     */
    static Run simulate(long budgetMicros, LongToDoubleFunction yieldPerMinute, long seed) {
        Span span = simulate(budgetMicros, yieldPerMinute, seed, T0, PERIOD.end(1_000_000), MAX_PLAY_DELAY_MS);
        return new Run(span.states(), span.deliveredByPeriod().get(1_000_000L), budgetMicros,
                span.maxTrackingError().get(1_000_000L));
    }

    record Span(List<PacingState> states, java.util.Map<Long, Long> deliveredByPeriod,
                java.util.Map<Long, Double> maxTrackingError) {
    }

    /**
     * Runs the loop from {@code fromMs} to {@code toMs}, possibly across period boundaries. Served
     * ads play up to {@code maxPlayDelayMs} later, and are delivered (and attributed) at play time,
     * so ads served at the end of one period count towards the next, as they do in the real system.
     */
    static Span simulate(long budgetMicros, LongToDoubleFunction yieldPerMinute, long seed, long fromMs, long toMs,
                         long maxPlayDelayMs) {
        var config = new LineItemConfig("li", "camp", "adv", LineItemConfig.Tier.STANDARD, 40_000_000, budgetMicros);
        var pacer = new LineItemPacer(config, PERIOD);
        var random = new SplittableRandom(seed);
        long served = 0;
        long servedPeriod = PERIOD.index(fromMs);
        double throttle = LineItemPacer.INITIAL_THROTTLE;
        long stopAt = Long.MAX_VALUE;
        var deliveredByMinute = new java.util.TreeMap<Long, Long>();
        var states = new ArrayList<PacingState>();
        var maxTrackingError = new java.util.HashMap<Long, Double>();

        // One-second steps: the ad server serves at the current throttle until its stop threshold.
        for (long t = fromMs; t < toMs; t += 1_000) {
            if (PERIOD.index(t) != servedPeriod) {
                // The ad server's served counter is per period.
                servedPeriod = PERIOD.index(t);
                served = 0;
                stopAt = Long.MAX_VALUE;
            }
            if (served < stopAt) {
                double perSecond = throttle * yieldPerMinute.applyAsDouble(t - fromMs) / 60 * (0.7 + 0.6 * random.nextDouble());
                long servedNow = (long) (perSecond / DELIVERY_RATIO);
                served += servedNow;
                long playedAt = t + (maxPlayDelayMs > 0 ? random.nextLong(maxPlayDelayMs) : 0);
                deliveredByMinute.merge(Math.floorDiv(playedAt, MINUTE) * MINUTE, (long) (servedNow * DELIVERY_RATIO), Long::sum);
            }
            if ((t - fromMs) % 10_000 == 0) {
                // Report every minute whose end is at least REPORT_LAG_MS - 60 s in the past.
                long horizon = Math.floorDiv(t - (REPORT_LAG_MS - MINUTE), MINUTE) * MINUTE;
                for (var e : deliveredByMinute.headMap(horizon).entrySet()) {
                    pacer.delivered(e.getKey(), e.getValue());
                }
                PacingState s = pacer.tick(t, horizon, served);
                throttle = s.throttle();
                stopAt = s.stopAtServedMicros();
                states.add(s);
                if (t - PERIOD.start(PERIOD.index(t)) > 5 * MINUTE) {
                    maxTrackingError.merge(s.periodIndex(),
                            Math.abs(s.deliveredMicros() - s.targetMicros()) / (double) budgetMicros, Math::max);
                }
            }
        }
        var deliveredByPeriod = new java.util.HashMap<Long, Long>();
        deliveredByMinute.forEach((minute, spend) -> deliveredByPeriod.merge(PERIOD.index(minute), spend, Long::sum));
        return new Span(states, deliveredByPeriod, maxTrackingError);
    }

    @Test
    void spendsTheBudgetEvenlyAndStopsOnTime() {
        // $10/min available, $90 budget over 30 min: needs a throttle around 0.3.
        Run run = simulate(90_000_000, t -> 10_000_000, 1);

        assertThat((double) run.finalDelivered() / run.budget()).isBetween(0.97, 1.005);
        assertThat(run.maxTrackingError()).as("delivered stays close to the even curve").isLessThan(0.04);
        assertThat(run.states().get(run.states().size() - 1).status()).isIn(PacingState.Status.EXHAUSTED, PacingState.Status.ON_PACE);
    }

    @Test
    void absorbsATrafficSurgeWithoutOverspending() {
        // Traffic triples halfway through.
        Run run = simulate(90_000_000, t -> t < 15 * MINUTE ? 10_000_000 : 30_000_000, 2);

        assertThat((double) run.finalDelivered() / run.budget()).isBetween(0.97, 1.005);
        assertThat(run.maxTrackingError()).isLessThan(0.06);
    }

    @Test
    void canSpendNearlyAllAvailableTraffic() {
        // The budget needs 90% of everything the line item could win.
        Run run = simulate(270_000_000, t -> 10_000_000, 5);

        assertThat((double) run.finalDelivered() / run.budget()).isBetween(0.97, 1.005);
        assertThat(run.maxTrackingError()).isLessThan(0.04);
    }

    @Test
    void reportsUnderDeliveryWhenTrafficCannotCoverTheBudget() {
        // $5/min available, $300 budget: impossible.
        Run run = simulate(300_000_000, t -> 5_000_000, 3);

        PacingState last = run.states().get(run.states().size() - 1);
        assertThat(last.throttle()).isEqualTo(1.0);
        assertThat(last.status()).isEqualTo(PacingState.Status.UNDER_DELIVERING);
        assertThat(run.finalDelivered()).isLessThan(run.budget());
    }

    @Test
    void holdsBackWhenTrafficIsPlentiful() {
        // $100/min available for a $30 budget: throttle must go very low and stay smooth.
        Run run = simulate(30_000_000, t -> 100_000_000, 4);

        assertThat((double) run.finalDelivered() / run.budget()).isBetween(0.97, 1.005);
        assertThat(run.maxTrackingError()).as("brief early overshoot while learning the yield").isLessThan(0.09);
        assertThat(run.states().get(run.states().size() / 2).throttle()).isLessThan(0.05);
    }

    @Test
    void startingLateInAPeriodDoesNotOverspendTheNext() {
        // Found live: the stack started 85% into a period. Chasing 85% of the budget at once drove
        // the throttle to 1, the next period inherited it, and ads served at throttle 1 just before
        // the boundary played (and counted) in the next period, which overspent by up to 51%.
        // Plentiful traffic ($100/min for a $30 budget) and a play delay of up to a minute.
        long from = T0 + (long) (0.85 * 30 * MINUTE);
        Span span = simulate(30_000_000, t -> 100_000_000, 6, from, PERIOD.end(1_000_001), MAX_PLAY_DELAY_MS);

        assertThat(span.deliveredByPeriod().get(1_000_000L)).as("the partial period catches up at most 2x the even rate")
                .isLessThan(30_000_000L * 15 / 100 * 2 + 3_000_000);
        double next = span.deliveredByPeriod().get(1_000_001L) / 30_000_000.0;
        assertThat(next).as("the first full period").isBetween(0.97, 1.01);
        assertThat(span.maxTrackingError().get(1_000_001L)).isLessThan(0.09);
    }

    @Test
    void stopsOnServedSpendBeforeDeliveryIsReported() {
        var config = new LineItemConfig("li", "c", "a", LineItemConfig.Tier.STANDARD, 1, 10_000_000);
        var pacer = new LineItemPacer(config, PERIOD);
        // Nothing reported yet (horizon at period start), but $11 already served. Until the
        // delivery ratio is observed, the pacer assumes every served ad plays, so it stops at the
        // budget in served spend: it cannot overspend however many served ads go unplayed.
        PacingState s = pacer.tick(T0 + 5 * MINUTE, T0, 11_000_000);

        assertThat(s.stopAtServedMicros()).isEqualTo(10_000_000);
        assertThat(s.status()).isEqualTo(PacingState.Status.EXHAUSTED);
        assertThat(s.throttle()).isZero();
    }

    @Test
    void startsAFreshBudgetEachPeriod() {
        var config = new LineItemConfig("li", "c", "a", LineItemConfig.Tier.STANDARD, 1, 10_000_000);
        var pacer = new LineItemPacer(config, PERIOD);
        pacer.delivered(T0, 10_000_000);
        assertThat(pacer.tick(T0 + 2 * MINUTE, T0 + 2 * MINUTE, 12_500_000).status()).isEqualTo(PacingState.Status.EXHAUSTED);

        long next = PERIOD.end(1_000_000);
        PacingState s = pacer.tick(next + MINUTE, next, 0);
        assertThat(s.periodIndex()).isEqualTo(1_000_001);
        assertThat(s.deliveredMicros()).isZero();
        assertThat(s.status()).isNotEqualTo(PacingState.Status.EXHAUSTED);
        assertThat(s.throttle()).isPositive();
    }
}
