package dev.primetech.ads.common;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static dev.primetech.ads.common.TestEvents.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class EventValidatorTest {
    private final EventValidator validator = new EventValidator(Duration.ofMinutes(5), Duration.ofHours(24));

    @Test
    void acceptsWellFormedEvents() {
        assertThat(validator.validate(TestEvents.impression(), NOW)).isEmpty();
        assertThat(validator.validate(TestEvents.midpoint(), NOW)).isEmpty();
    }

    @Test
    void acceptsEventWithoutDeviceId() {
        var e = TestEvents.midpoint();
        var noDevice = new ClientEvent(e.eventId(), e.type(), e.eventTimeMs(), e.sessionId(), e.adId(),
                e.creativeId(), e.campaignId(), e.lineItemId(), null, e.deviceType(), e.country(), false, null);
        assertThat(validator.validate(noDevice, NOW)).isEmpty();
    }

    @Test
    void rejectsUnknownType() {
        var e = TestEvents.midpoint();
        var bad = new ClientEvent(e.eventId(), null, e.eventTimeMs(), e.sessionId(), e.adId(), e.creativeId(),
                e.campaignId(), e.lineItemId(), e.deviceId(), e.deviceType(), e.country(), e.consent(), null);
        assertThat(validator.validate(bad, NOW)).contains(RejectReason.UNKNOWN_EVENT_TYPE);
    }

    @Test
    void rejectsMissingAndBlankFields() {
        var e = TestEvents.midpoint();
        var blankSession = new ClientEvent(e.eventId(), e.type(), e.eventTimeMs(), " ", e.adId(), e.creativeId(),
                e.campaignId(), e.lineItemId(), e.deviceId(), e.deviceType(), e.country(), e.consent(), null);
        var noConsent = new ClientEvent(e.eventId(), e.type(), e.eventTimeMs(), e.sessionId(), e.adId(),
                e.creativeId(), e.campaignId(), e.lineItemId(), e.deviceId(), e.deviceType(), e.country(), null, null);
        assertThat(validator.validate(blankSession, NOW)).contains(RejectReason.MISSING_FIELD);
        assertThat(validator.validate(noConsent, NOW)).contains(RejectReason.MISSING_FIELD);
    }

    @Test
    void rejectsOverlongIds() {
        var e = TestEvents.midpoint();
        var longId = "x".repeat(EventValidator.MAX_ID_LENGTH + 1);
        var bad = new ClientEvent(longId, e.type(), e.eventTimeMs(), e.sessionId(), e.adId(), e.creativeId(),
                e.campaignId(), e.lineItemId(), e.deviceId(), e.deviceType(), e.country(), e.consent(), null);
        assertThat(validator.validate(bad, NOW)).contains(RejectReason.FIELD_TOO_LONG);
    }

    @Test
    void rejectsBadCountry() {
        var e = TestEvents.midpoint();
        var bad = new ClientEvent(e.eventId(), e.type(), e.eventTimeMs(), e.sessionId(), e.adId(), e.creativeId(),
                e.campaignId(), e.lineItemId(), e.deviceId(), e.deviceType(), "usa", e.consent(), null);
        assertThat(validator.validate(bad, NOW)).contains(RejectReason.INVALID_COUNTRY);
    }

    @Test
    void enforcesEventTimeBounds() {
        var e = TestEvents.midpoint();
        long justInsideFuture = NOW + Duration.ofMinutes(5).toMillis();
        long justInsidePast = NOW - Duration.ofHours(24).toMillis();
        assertThat(validator.validate(withTime(e, justInsideFuture), NOW)).isEmpty();
        assertThat(validator.validate(withTime(e, justInsidePast), NOW)).isEmpty();
        assertThat(validator.validate(withTime(e, justInsideFuture + 1), NOW))
                .contains(RejectReason.EVENT_TIME_IN_FUTURE);
        assertThat(validator.validate(withTime(e, justInsidePast - 1), NOW))
                .contains(RejectReason.EVENT_TIME_TOO_OLD);
    }

    @Test
    void enforcesPriceRules() {
        var imp = TestEvents.impression();
        var mid = TestEvents.midpoint();
        assertThat(validator.validate(withPrice(imp, null), NOW)).contains(RejectReason.MISSING_PRICE);
        assertThat(validator.validate(withPrice(imp, -1L), NOW)).contains(RejectReason.NEGATIVE_PRICE);
        assertThat(validator.validate(withPrice(imp, 0L), NOW)).isEmpty();
        assertThat(validator.validate(withPrice(mid, 10L), NOW)).contains(RejectReason.PRICE_ON_NON_BILLABLE);
    }

    private static ClientEvent withTime(ClientEvent e, long t) {
        return new ClientEvent(e.eventId(), e.type(), t, e.sessionId(), e.adId(), e.creativeId(), e.campaignId(),
                e.lineItemId(), e.deviceId(), e.deviceType(), e.country(), e.consent(), e.priceMicros());
    }

    private static ClientEvent withPrice(ClientEvent e, Long price) {
        return new ClientEvent(e.eventId(), e.type(), e.eventTimeMs(), e.sessionId(), e.adId(), e.creativeId(),
                e.campaignId(), e.lineItemId(), e.deviceId(), e.deviceType(), e.country(), e.consent(), price);
    }
}
