package dev.primetech.ads.common;

/**
 * Per line item, per event-time minute delivery totals, written to {@link Topics#LINE_ITEM_MINUTES}.
 *
 * <p>The topic is an upsert stream: when late events arrive inside the allowed lateness, the
 * minute is emitted again with a higher {@code revision} and the full corrected totals (not a
 * delta). Readers keep the highest revision per {@link #key()}; the topic is compacted on that key.
 */
public record LineItemMinute(
        String lineItemId,
        String campaignId,
        String advertiserId,
        long windowStartMs,
        long windowEndMs,
        int revision,
        long impressions,
        long firstQuartiles,
        long midpoints,
        long thirdQuartiles,
        long completes,
        long clicks,
        long spendMicros,
        long ivtEvents,
        long emittedAtMs) {

    public String key() {
        return lineItemId + "|" + windowStartMs;
    }
}
