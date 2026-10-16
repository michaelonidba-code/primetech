package dev.primetech.ads.common.pacing;

import java.time.Duration;

/**
 * Budgets reset every period. Periods are aligned to the epoch, so every service computes the same
 * period from the clock alone. Real deals pace per day; the period is configurable so a whole
 * "day" of pacing can be watched in a short demo.
 */
public record PacingPeriod(long lengthMs) {

    public static PacingPeriod of(Duration length) {
        return new PacingPeriod(length.toMillis());
    }

    public long index(long timeMs) {
        return Math.floorDiv(timeMs, lengthMs);
    }

    public long start(long index) {
        return index * lengthMs;
    }

    public long end(long index) {
        return (index + 1) * lengthMs;
    }

    /** Fraction of the period elapsed at {@code timeMs}, between 0 and 1. */
    public double elapsed(long timeMs) {
        long index = index(timeMs);
        return (double) (timeMs - start(index)) / lengthMs;
    }
}
