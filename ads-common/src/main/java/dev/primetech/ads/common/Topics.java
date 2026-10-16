package dev.primetech.ads.common;

public final class Topics {
    /** Validated, privacy-scrubbed events, keyed by session id. */
    public static final String AD_EVENTS = "ad-events.v1";
    /** Events that failed validation, kept with the reason for auditing and replay. */
    public static final String AD_EVENTS_DLQ = "ad-events-dlq.v1";

    private Topics() {
    }
}
