package dev.primetech.ads.common;

/**
 * An event as a player sends it to the ingest gateway. It may carry a raw device id, so it
 * must never be written to Kafka as-is; {@link EventNormalizer} turns it into an {@link AdEvent}.
 *
 * @param eventId       client-generated UUID; the idempotency key used for de-duplication downstream
 * @param type          playback event type
 * @param eventTimeMs   when the event happened on the device (epoch millis)
 * @param sessionId     playback session; all events of one ad break share it
 * @param adId          ad (creative placement) that was shown
 * @param creativeId    creative asset
 * @param campaignId    advertiser campaign
 * @param lineItemId    line item inside the campaign that won the decision
 * @param deviceId      raw device identifier, only used when {@code consent} is true
 * @param deviceType    device class
 * @param country       ISO 3166-1 alpha-2 country code
 * @param consent       whether the viewer allows identifier-based ad tracking
 * @param priceMicros   clearing price in micro-currency units; only set on impressions
 */
public record ClientEvent(
        String eventId,
        EventType type,
        Long eventTimeMs,
        String sessionId,
        String adId,
        String creativeId,
        String campaignId,
        String lineItemId,
        String deviceId,
        DeviceType deviceType,
        String country,
        Boolean consent,
        Long priceMicros) {
}
