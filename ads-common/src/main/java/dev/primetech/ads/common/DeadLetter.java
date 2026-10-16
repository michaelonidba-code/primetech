package dev.primetech.ads.common;

/**
 * A rejected event written to {@link Topics#AD_EVENTS_DLQ}. The original event is kept for
 * debugging and replay, but with its raw device id removed: the DLQ gets the same privacy rules
 * as the main topic.
 */
public record DeadLetter(RejectReason reason, long receivedTimeMs, ClientEvent event) {

    public static DeadLetter of(RejectReason reason, long receivedTimeMs, ClientEvent e) {
        var scrubbed = new ClientEvent(e.eventId(), e.type(), e.eventTimeMs(), e.sessionId(), e.adId(),
                e.creativeId(), e.campaignId(), e.lineItemId(), null, e.deviceType(), e.country(), e.consent(),
                e.priceMicros());
        return new DeadLetter(reason, receivedTimeMs, scrubbed);
    }
}
