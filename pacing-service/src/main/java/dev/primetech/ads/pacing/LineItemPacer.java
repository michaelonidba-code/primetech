package dev.primetech.ads.pacing;

import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.pacing.PacingPeriod;
import dev.primetech.ads.common.pacing.PacingState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Paces one line item: spends its period budget evenly by setting the probability that it enters
 * an auction (the throttle). Pure logic, no I/O, so it can be tested in a closed loop.
 *
 * <h2>Inputs</h2>
 * <ul>
 *   <li><b>Delivered spend</b> per event-time minute from the stream processor: authoritative
 *       (de-duplicated, IVT excluded) but only known for minutes the watermark has closed, so it
 *       lags real time by a minute or so. {@code horizonMs} is how far it is known.</li>
 *   <li><b>Served spend</b>: the ad server's own counter of what it has sold. Immediate, but it
 *       overstates delivery because viewers abandon breaks before every served ad plays.</li>
 * </ul>
 *
 * <h2>Control law</h2>
 * The pacer learns a <em>yield</em>: delivered spend per minute the line item would get at
 * throttle 1. It is estimated every tick from the growth of served spend (immediate) times the
 * delivered/served ratio, divided by the throttle that was in force. Learning from delivered
 * spend instead would lag by over a minute and overspend early; in simulation that let delivery
 * stray 15% from the even curve when traffic was plentiful, against 3% with served spend.
 *
 * <p>Each tick sets {@code throttle = required rate / yield}, where the required rate spreads the
 * remaining budget over the remaining time. Using remaining budget makes past shortfall or
 * overshoot self-correcting (integral action). Each tick can cut the throttle by up to 4× but only
 * double it: overspend cannot be undone, underspend can be caught up. Catching up is capped at
 * {@link #MAX_CATCH_UP} times the even rate, so a line item that starts late in a period (or comes
 * back from an outage) spends what it reasonably can instead of its whole budget in one burst.
 *
 * <h2>Hard stop</h2>
 * Delivered spend lags, so stopping on it alone overspends. The pacer converts the remaining
 * budget into served spend using the observed delivered/served ratio and publishes
 * {@code stopAtServedMicros}; the ad server stops the moment its live served counter reaches it.
 */
public final class LineItemPacer {
    static final double MIN_THROTTLE = 0.0005;
    static final double INITIAL_THROTTLE = 0.02;
    static final double YIELD_SMOOTHING = 0.2;
    /** Delivered/served ratio assumed until one is observed: 1 never lets served spend pass the budget. */
    static final double DEFAULT_DELIVERY_RATIO = 1.0;
    /** Tolerance around the even-delivery curve before a line item counts as ahead or behind. */
    static final double PACE_TOLERANCE = 0.05;
    /** Fastest catch-up, as a multiple of the even spending rate. */
    static final double MAX_CATCH_UP = 2.0;
    /**
     * Typical time from serving an ad to playing it: a pod's ads play one after another, so the
     * price-weighted mean measured end to end is about 15 s. Delivery up to the horizon is compared
     * with what was served this much earlier. Comparing with everything served up to the horizon
     * counts ads that have not played yet, which understated the delivery ratio and the yield and
     * overshot; allowing too much (30 s, the first guess) counted delivered spend as still in
     * flight and under-delivered by 6%.
     */
    static final long SERVE_TO_PLAY_MS = 15_000;
    private static final long MINUTE_MS = 60_000;

    private final LineItemConfig config;
    private final PacingPeriod period;

    private long periodIndex = Long.MIN_VALUE;
    private final TreeMap<Long, Long> spendByMinute = new TreeMap<>();
    /** (time, served spend) at each tick, to know what had been served by the delivery horizon. */
    private final List<long[]> servedHistory = new ArrayList<>();
    private double throttle = INITIAL_THROTTLE;
    private double yieldPerMinute = Double.NaN;
    private long lastTickMs = Long.MIN_VALUE;
    private long lastServedMicros;
    private PacingState last;

    public LineItemPacer(LineItemConfig config, PacingPeriod period) {
        this.config = config;
        this.period = period;
    }

    /** Records the latest total for one event-time minute (later revisions replace earlier ones). */
    public synchronized void delivered(long minuteStartMs, long spendMicros) {
        // Minutes of the next period can arrive before the tick that rolls over to it; keep them.
        if (period.index(minuteStartMs) >= periodIndex) {
            spendByMinute.put(minuteStartMs, spendMicros);
        }
    }

    /**
     * One control step.
     *
     * @param horizonMs    delivered spend is known for minutes ending at or before this time
     * @param servedMicros the ad server's served-spend counter for this period, now
     */
    public synchronized PacingState tick(long nowMs, long horizonMs, long servedMicros) {
        rollOver(nowMs);
        long budget = config.periodBudgetMicros();
        long start = period.start(periodIndex);
        long end = period.end(periodIndex);
        long horizon = Math.max(start, Math.min(horizonMs, nowMs));

        long delivered = deliveredBefore(horizon);
        long servedAtHorizon = Math.min(servedMicros, servedAt(Math.max(start, horizon - SERVE_TO_PLAY_MS)));
        double ratio = servedAtHorizon > 0
                ? clamp((double) delivered / servedAtHorizon, 0.3, 1.0)
                : DEFAULT_DELIVERY_RATIO;
        long stopAt = servedAtHorizon + (long) (Math.max(0, budget - delivered) / ratio);
        // Spend already served but not yet reported, expected to turn into delivery.
        long inFlight = (long) ((servedMicros - servedAtHorizon) * ratio);
        long committed = delivered + inFlight;
        long target = (long) (budget * ((double) (horizon - start) / (end - start)));

        updateYield(nowMs, servedMicros, ratio);

        PacingState.Status status;
        if (delivered >= budget || servedMicros >= stopAt) {
            throttle = 0;
            status = PacingState.Status.EXHAUSTED;
        } else {
            double minutesLeft = Math.max(0.5, (end - nowMs) / (double) MINUTE_MS);
            double evenPerMinute = budget / ((end - start) / (double) MINUTE_MS);
            double requiredPerMinute = Math.min(Math.max(0, budget - committed) / minutesLeft, MAX_CATCH_UP * evenPerMinute);
            double wanted;
            if (Double.isNaN(yieldPerMinute) || yieldPerMinute <= 0) {
                // Nothing learned yet: probe upwards while behind.
                wanted = committed < target ? Math.max(throttle, INITIAL_THROTTLE) * 2 : Math.max(throttle, INITIAL_THROTTLE);
            } else {
                wanted = requiredPerMinute / yieldPerMinute;
            }
            double previous = Math.max(throttle, MIN_THROTTLE);
            // Cut fast, grow cautiously: overspend cannot be undone, underspend can be caught up.
            throttle = clamp(clamp(wanted, previous / 4, previous * 2), MIN_THROTTLE, 1.0);

            double gap = (double) (delivered - target) / budget;
            if (gap > PACE_TOLERANCE) {
                status = PacingState.Status.AHEAD;
            } else if (gap < -PACE_TOLERANCE) {
                status = throttle >= 1.0 ? PacingState.Status.UNDER_DELIVERING : PacingState.Status.BEHIND;
            } else {
                status = PacingState.Status.ON_PACE;
            }
        }

        servedHistory.add(new long[] {nowMs, servedMicros});
        last = new PacingState(config.lineItemId(), periodIndex, budget, delivered, target, throttle, stopAt, status, nowMs);
        return last;
    }

    public synchronized PacingState last() {
        return last;
    }

    public LineItemConfig config() {
        return config;
    }

    private void rollOver(long nowMs) {
        long index = period.index(nowMs);
        if (index == periodIndex) {
            return;
        }
        periodIndex = index;
        long start = period.start(index);
        spendByMinute.headMap(start).clear();
        servedHistory.clear();
        // Keep the learned yield (traffic in the next period looks like this one) but not the
        // throttle: a throttle raised to catch up, or cut to zero once spent, says nothing about
        // what a fresh budget needs. Start the period at the even rate.
        if (Double.isNaN(yieldPerMinute) || yieldPerMinute <= 0) {
            throttle = INITIAL_THROTTLE;
        } else {
            double evenPerMinute = config.periodBudgetMicros() / (period.lengthMs() / (double) MINUTE_MS);
            throttle = clamp(evenPerMinute / yieldPerMinute, MIN_THROTTLE, 1.0);
        }
        // Served counters restart at zero each period.
        lastTickMs = Long.MIN_VALUE;
        lastServedMicros = 0;
    }

    private long deliveredBefore(long horizon) {
        long sum = 0;
        for (Map.Entry<Long, Long> e : spendByMinute.headMap(horizon).entrySet()) {
            if (e.getKey() + MINUTE_MS <= horizon) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    /**
     * Folds the served spend since the last tick into the yield estimate: what the throttle in force
     * since then produced, scaled to throttle 1 and to delivered spend.
     */
    private void updateYield(long nowMs, long servedMicros, double deliveryRatio) {
        if (lastTickMs != Long.MIN_VALUE && nowMs > lastTickMs && throttle > MIN_THROTTLE * 2) {
            double perMinute = (servedMicros - lastServedMicros) * deliveryRatio * MINUTE_MS / (nowMs - lastTickMs);
            double sample = perMinute / throttle;
            yieldPerMinute = Double.isNaN(yieldPerMinute) ? sample
                    : YIELD_SMOOTHING * sample + (1 - YIELD_SMOOTHING) * yieldPerMinute;
        }
        lastTickMs = nowMs;
        lastServedMicros = servedMicros;
    }

    /** Served spend at a past moment, interpolated between ticks. */
    private long servedAt(long timeMs) {
        long[] before = null;
        for (long[] s : servedHistory) {
            if (s[0] <= timeMs) {
                before = s;
            } else {
                if (before == null) {
                    return 0;
                }
                double f = (double) (timeMs - before[0]) / (s[0] - before[0]);
                return before[1] + (long) ((s[1] - before[1]) * f);
            }
        }
        return before == null ? 0 : before[1];
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
