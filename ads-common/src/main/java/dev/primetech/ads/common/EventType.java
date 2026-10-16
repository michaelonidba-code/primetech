package dev.primetech.ads.common;

/**
 * Client-side ad playback events, modelled on the VAST tracking events a CTV player fires.
 * Quartiles are cumulative: a COMPLETE implies the player also passed every earlier quartile.
 */
public enum EventType {
    IMPRESSION,
    FIRST_QUARTILE,
    MIDPOINT,
    THIRD_QUARTILE,
    COMPLETE,
    CLICK;

    /** Only impressions are billable, so only they carry a clearing price. */
    public boolean isBillable() {
        return this == IMPRESSION;
    }
}
