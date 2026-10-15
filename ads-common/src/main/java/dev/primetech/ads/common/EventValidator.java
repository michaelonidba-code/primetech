package dev.primetech.ads.common;

import java.time.Duration;
import java.util.Optional;

/**
 * Stateless, per-event checks done at the edge. Anything that needs state (duplicates,
 * invalid-traffic patterns) is left to the stream processor.
 */
public final class EventValidator {
    static final int MAX_ID_LENGTH = 64;

    private final long maxFutureSkewMs;
    private final long maxAgeMs;

    /**
     * @param maxFutureSkew how far ahead of server time a device clock may be
     * @param maxAge        oldest event accepted; late events within this bound are kept so the
     *                      stream processor can still count them
     */
    public EventValidator(Duration maxFutureSkew, Duration maxAge) {
        this.maxFutureSkewMs = maxFutureSkew.toMillis();
        this.maxAgeMs = maxAge.toMillis();
    }

    public static EventValidator withDefaults() {
        return new EventValidator(Duration.ofMinutes(5), Duration.ofHours(24));
    }

    /** Returns the first problem found, or empty if the event is acceptable. */
    public Optional<RejectReason> validate(ClientEvent e, long nowMs) {
        if (e.type() == null) {
            return Optional.of(RejectReason.UNKNOWN_EVENT_TYPE);
        }
        if (isBlank(e.eventId()) || isBlank(e.sessionId()) || isBlank(e.adId())
                || isBlank(e.creativeId()) || isBlank(e.campaignId()) || isBlank(e.lineItemId())
                || e.eventTimeMs() == null || e.deviceType() == null || e.country() == null
                || e.consent() == null) {
            return Optional.of(RejectReason.MISSING_FIELD);
        }
        if (tooLong(e.eventId()) || tooLong(e.sessionId()) || tooLong(e.adId())
                || tooLong(e.creativeId()) || tooLong(e.campaignId()) || tooLong(e.lineItemId())
                || tooLong(e.deviceId()) || tooLong(e.auctionId())) {
            return Optional.of(RejectReason.FIELD_TOO_LONG);
        }
        if (!isCountryCode(e.country())) {
            return Optional.of(RejectReason.INVALID_COUNTRY);
        }
        if (e.eventTimeMs() > nowMs + maxFutureSkewMs) {
            return Optional.of(RejectReason.EVENT_TIME_IN_FUTURE);
        }
        if (e.eventTimeMs() < nowMs - maxAgeMs) {
            return Optional.of(RejectReason.EVENT_TIME_TOO_OLD);
        }
        if (e.type().isBillable()) {
            if (e.priceMicros() == null) {
                return Optional.of(RejectReason.MISSING_PRICE);
            }
            if (e.priceMicros() < 0) {
                return Optional.of(RejectReason.NEGATIVE_PRICE);
            }
        } else if (e.priceMicros() != null) {
            return Optional.of(RejectReason.PRICE_ON_NON_BILLABLE);
        }
        return Optional.empty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean tooLong(String s) {
        return s != null && s.length() > MAX_ID_LENGTH;
    }

    private static boolean isCountryCode(String s) {
        return s.length() == 2 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1));
    }
}
