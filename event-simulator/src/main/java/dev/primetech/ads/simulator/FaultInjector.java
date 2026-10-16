package dev.primetech.ads.simulator;

import dev.primetech.ads.common.ClientEvent;

import java.util.random.RandomGenerator;

/**
 * Makes the traffic as messy as real device traffic, so the pipeline's handling of it can be
 * tested: retried sends (duplicates), events buffered on a device that come in late, and broken
 * payloads.
 */
final class FaultInjector {
    enum Fault { NONE, DUPLICATE, LATE, INVALID }

    private final double duplicateRate;
    private final double lateRate;
    private final double invalidRate;

    FaultInjector(double duplicateRate, double lateRate, double invalidRate) {
        if (duplicateRate + lateRate + invalidRate > 1.0) {
            throw new IllegalArgumentException("fault rates must sum to at most 1");
        }
        this.duplicateRate = duplicateRate;
        this.lateRate = lateRate;
        this.invalidRate = invalidRate;
    }

    Fault choose(RandomGenerator random) {
        double r = random.nextDouble();
        if (r < duplicateRate) {
            return Fault.DUPLICATE;
        }
        r -= duplicateRate;
        if (r < lateRate) {
            return Fault.LATE;
        }
        r -= lateRate;
        if (r < invalidRate) {
            return Fault.INVALID;
        }
        return Fault.NONE;
    }

    /** Breaks the event in one of the ways the gateway is expected to catch. */
    static ClientEvent corrupt(ClientEvent e, long nowMs, RandomGenerator random) {
        return switch (random.nextInt(4)) {
            case 0 -> copy(e, e.eventTimeMs(), "usa", e.priceMicros(), e.campaignId());
            case 1 -> copy(e, nowMs + 3_600_000L, e.country(), e.priceMicros(), e.campaignId());
            case 2 -> copy(e, e.eventTimeMs(), e.country(), e.type().isBillable() ? -5L : 7L, e.campaignId());
            default -> copy(e, e.eventTimeMs(), e.country(), e.priceMicros(), null);
        };
    }

    private static ClientEvent copy(ClientEvent e, long time, String country, Long price, String campaignId) {
        return new ClientEvent(e.eventId(), e.type(), time, e.sessionId(), e.adId(), e.creativeId(), campaignId,
                e.lineItemId(), e.deviceId(), e.deviceType(), country, e.consent(), price);
    }
}
