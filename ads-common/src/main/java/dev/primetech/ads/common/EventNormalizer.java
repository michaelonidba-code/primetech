package dev.primetech.ads.common;

/** Converts a validated {@link ClientEvent} to the canonical {@link AdEvent}, applying privacy rules. */
public final class EventNormalizer {
    private final DeviceIdHasher hasher;

    public EventNormalizer(DeviceIdHasher hasher) {
        this.hasher = hasher;
    }

    public AdEvent normalize(ClientEvent e, long receivedTimeMs) {
        // Without consent the identifier is dropped entirely, not just hashed.
        String deviceIdHash = e.consent() && e.deviceId() != null && !e.deviceId().isBlank()
                ? hasher.hash(e.deviceId())
                : null;
        return new AdEvent(
                AdEvent.SCHEMA_VERSION,
                e.eventId(),
                e.type(),
                e.eventTimeMs(),
                receivedTimeMs,
                e.sessionId(),
                e.adId(),
                e.creativeId(),
                e.campaignId(),
                e.lineItemId(),
                deviceIdHash,
                e.deviceType(),
                e.country(),
                e.consent(),
                e.priceMicros(),
                e.auctionId());
    }
}
