package dev.primetech.ads.common;

public final class Topics {
    /** Validated, privacy-scrubbed events, keyed by session id. May contain duplicates. */
    public static final String AD_EVENTS = "ad-events.v1";
    /** Events that failed validation, kept with the reason for auditing and replay. */
    public static final String AD_EVENTS_DLQ = "ad-events-dlq.v1";
    /** Campaign reference data, compacted, keyed by campaign id. */
    public static final String CAMPAIGN_METADATA = "campaign-metadata.v1";
    /** De-duplicated, enriched, IVT-flagged events, written transactionally. Keyed by session id. */
    public static final String AD_EVENTS_CLEAN = "ad-events-clean.v1";
    /** Per line item per minute delivery totals, an upsert stream keyed by line item and minute. */
    public static final String LINE_ITEM_MINUTES = "line-item-minutes.v1";

    private Topics() {
    }
}
