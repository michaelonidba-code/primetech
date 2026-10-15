package dev.primetech.ads.common;

/**
 * Canonical ad event written to the {@link Topics#AD_EVENTS} topic. It never contains a raw device
 * identifier: {@code deviceIdHash} is a keyed hash and is null when the viewer did not consent.
 */
public record AdEvent(
        int schemaVersion,
        String eventId,
        EventType type,
        long eventTimeMs,
        long receivedTimeMs,
        String sessionId,
        String adId,
        String creativeId,
        String campaignId,
        String lineItemId,
        String deviceIdHash,
        DeviceType deviceType,
        String country,
        boolean consent,
        Long priceMicros,
        String auctionId) {

    public static final int SCHEMA_VERSION = 1;

    public AdEvent(int schemaVersion, String eventId, EventType type, long eventTimeMs, long receivedTimeMs,
                   String sessionId, String adId, String creativeId, String campaignId, String lineItemId,
                   String deviceIdHash, DeviceType deviceType, String country, boolean consent, Long priceMicros) {
        this(schemaVersion, eventId, type, eventTimeMs, receivedTimeMs, sessionId, adId, creativeId, campaignId,
                lineItemId, deviceIdHash, deviceType, country, consent, priceMicros, null);
    }
}
