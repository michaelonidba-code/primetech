package dev.primetech.ads.common;

/**
 * Reference data the stream processor joins onto every event. Published to the compacted
 * {@link Topics#CAMPAIGN_METADATA} topic keyed by campaign id, so the latest value per campaign wins.
 */
public record CampaignMetadata(
        String campaignId,
        String campaignName,
        String advertiserId,
        String advertiserName) {
}
