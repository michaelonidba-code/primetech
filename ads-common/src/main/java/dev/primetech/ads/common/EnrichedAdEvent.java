package dev.primetech.ads.common;

/**
 * A de-duplicated event with campaign metadata attached and an invalid-traffic verdict. Written
 * to {@link Topics#AD_EVENTS_CLEAN}, the topic reporting (Druid) and billing read from.
 *
 * <p>Flat rather than wrapping {@link AdEvent} so analytics stores can ingest it without
 * flattening rules. IVT events are kept and flagged, not dropped: advertisers are entitled to see
 * what was filtered, and the decision can be revisited.
 *
 * @param campaignName   null if metadata for the campaign never arrived
 * @param ivtReason      null for valid traffic, otherwise why the event is not billable
 * @param processedTimeMs when the stream processor emitted this record (processing time)
 * @param auctionId      the ad server decision that served the ad, if any (joins to ad-decisions.v1)
 */
public record EnrichedAdEvent(
        int schemaVersion,
        String eventId,
        EventType type,
        long eventTimeMs,
        long receivedTimeMs,
        long processedTimeMs,
        String sessionId,
        String adId,
        String creativeId,
        String campaignId,
        String lineItemId,
        String campaignName,
        String advertiserId,
        String advertiserName,
        String deviceIdHash,
        DeviceType deviceType,
        String country,
        boolean consent,
        Long priceMicros,
        String ivtReason,
        String auctionId) {

    public static final int SCHEMA_VERSION = 1;

    public static EnrichedAdEvent of(AdEvent e, CampaignMetadata meta, long processedTimeMs) {
        return new EnrichedAdEvent(SCHEMA_VERSION, e.eventId(), e.type(), e.eventTimeMs(), e.receivedTimeMs(),
                processedTimeMs, e.sessionId(), e.adId(), e.creativeId(), e.campaignId(), e.lineItemId(),
                meta == null ? null : meta.campaignName(),
                meta == null ? null : meta.advertiserId(),
                meta == null ? null : meta.advertiserName(),
                e.deviceIdHash(), e.deviceType(), e.country(), e.consent(), e.priceMicros(), null, e.auctionId());
    }

    public boolean isIvt() {
        return ivtReason != null;
    }

    /** Billable spend: impressions only, and never invalid traffic. */
    public long billableMicros() {
        return !isIvt() && priceMicros != null ? priceMicros : 0;
    }

    public EnrichedAdEvent withIvt(String reason) {
        return new EnrichedAdEvent(schemaVersion, eventId, type, eventTimeMs, receivedTimeMs, processedTimeMs,
                sessionId, adId, creativeId, campaignId, lineItemId, campaignName, advertiserId, advertiserName,
                deviceIdHash, deviceType, country, consent, priceMicros, reason, auctionId);
    }
}
