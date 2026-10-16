package dev.primetech.ads.common.pacing;

/**
 * The pacing service's instruction for one line item in the current period, read by the ad server.
 *
 * @param throttle           probability the line item enters an auction (0..1)
 * @param stopAtServedMicros stop serving once the ad server's served-spend counter reaches this:
 *                           the authoritative remaining budget translated into served spend
 * @param deliveredMicros    billable spend reported by the stream processor (closed minutes only)
 * @param targetMicros       what even pacing would have delivered by the same point in time
 */
public record PacingState(
        String lineItemId,
        long periodIndex,
        long budgetMicros,
        long deliveredMicros,
        long targetMicros,
        double throttle,
        long stopAtServedMicros,
        Status status,
        long updatedAtMs) {

    public enum Status {
        /** Within tolerance of the even-delivery curve. */
        ON_PACE,
        /** Ahead of the curve: throttled down. */
        AHEAD,
        /** Behind the curve, but the throttle can still rise. */
        BEHIND,
        /** Behind with the throttle already at its maximum: there is not enough eligible traffic. */
        UNDER_DELIVERING,
        /** Budget spent (or committed by ads already served): no more serving this period. */
        EXHAUSTED
    }

    /** Redis keys shared by the pacing service (writer) and the ad server (reader). */
    public static final String REDIS_HASH = "pacing:state";

    public static String servedKey(long periodIndex, String lineItemId) {
        return "pacing:served:" + periodIndex + ":" + lineItemId;
    }
}
