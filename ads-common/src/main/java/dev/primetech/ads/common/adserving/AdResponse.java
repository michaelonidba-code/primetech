package dev.primetech.ads.common.adserving;

import java.util.List;

/**
 * The ads to play, in order. Fewer ads than slots requested (possibly none) means the rest went
 * unfilled. Players report {@link ServedAd#auctionId()} and the price on their tracking events.
 */
public record AdResponse(String requestId, List<ServedAd> ads) {

    /**
     * @param source       {@code direct} for a direct-sold line item, otherwise the winning DSP's name
     * @param priceMicros  clearing price of one impression, in micro-dollars
     */
    public record ServedAd(
            int position,
            String auctionId,
            String adId,
            String creativeId,
            String campaignId,
            String lineItemId,
            String advertiserId,
            int durationSeconds,
            long priceMicros,
            String source) {
    }
}
