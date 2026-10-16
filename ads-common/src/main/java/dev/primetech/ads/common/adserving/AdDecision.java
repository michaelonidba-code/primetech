package dev.primetech.ads.common.adserving;

import dev.primetech.ads.common.DeviceType;

import java.util.List;

/**
 * Audit record of one ad request, written to {@code ad-decisions.v1}: what was asked for, who bid,
 * who won each slot and at what price. With the auction id on tracking events, it lets reporting
 * compare what was sold with what was actually played.
 */
public record AdDecision(
        String requestId,
        long timestampMs,
        String sessionId,
        DeviceType deviceType,
        String country,
        boolean consent,
        int slotsRequested,
        int slotsFilled,
        int auctionType,
        long floorCpmMicros,
        long latencyMs,
        int directCandidates,
        int frequencyCapped,
        List<Slot> slots,
        List<Dsp> dsps) {

    /** @param bidCpmMicros the winner's bid; {@code clearingCpmMicros} is what it pays */
    public record Slot(int position, String source, String campaignId, String lineItemId, String advertiserId,
                       int durationSeconds, long bidCpmMicros, long clearingCpmMicros, int candidates) {
    }

    /** @param status BID, NO_BID, TIMEOUT or ERROR */
    public record Dsp(String name, String status, int bids, long latencyMs) {
    }
}
