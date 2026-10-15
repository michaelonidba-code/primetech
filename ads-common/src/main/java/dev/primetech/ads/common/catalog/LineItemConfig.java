package dev.primetech.ads.common.catalog;

/**
 * The commercial terms of a direct-sold line item, as the pacing service needs them. Published by
 * the ad server to the compacted {@code line-items.v1} topic.
 *
 * @param periodBudgetMicros spend allowed per pacing period, in micro-dollars
 */
public record LineItemConfig(
        String lineItemId,
        String campaignId,
        String advertiserId,
        Tier tier,
        long cpmMicros,
        long periodBudgetMicros) {

    /**
     * GUARANTEED line items (sponsorships, upfront deals) are served ahead of the auction whenever
     * pacing allows; STANDARD ones compete on price with programmatic demand.
     */
    public enum Tier { GUARANTEED, STANDARD }
}
