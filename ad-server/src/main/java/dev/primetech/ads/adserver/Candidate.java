package dev.primetech.ads.adserver;

/**
 * Something that could fill a slot: a direct-sold creative or a DSP bid.
 *
 * @param source       {@link #DIRECT} or the DSP name
 * @param impId        the OpenRTB imp a DSP bid is for (slot position as a string); null for direct
 * @param bidCpmMicros what the candidate offers: a direct line item's fixed CPM, or the DSP bid
 * @param nurl         DSP win notice URL with macros; null for direct
 * @param guaranteed   a guaranteed direct line item: wins ahead of the auction (priority tier)
 */
public record Candidate(
        String source,
        String impId,
        String campaignId,
        String lineItemId,
        String adId,
        String creativeId,
        String advertiserId,
        int durationSeconds,
        long bidCpmMicros,
        int frequencyCapPerHour,
        String nurl,
        boolean guaranteed) {

    public static final String DIRECT = "direct";

    public boolean isDirect() {
        return DIRECT.equals(source);
    }
}
