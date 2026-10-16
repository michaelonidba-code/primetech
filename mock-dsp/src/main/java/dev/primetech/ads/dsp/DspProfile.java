package dev.primetech.ads.dsp;

import java.util.List;
import java.util.Map;

/**
 * How a mock DSP behaves. Values are drawn from log-normal distributions, which is what both bid
 * prices and network latencies look like in practice: most near the median, a long right tail.
 *
 * @param bidRate          share of impressions the DSP bids on at all
 * @param cpmMedian        median value (USD CPM) the DSP's advertisers place on an impression
 * @param cpmSigma         spread of that value (log-normal sigma)
 * @param latencyMedianMs  median time to answer
 * @param latencySigma     spread of latency; a high value means a fat tail of timeouts
 * @param firstPriceShade  in first-price auctions, bid this fraction of value (bid shading)
 * @param advertisers      advertiser domains this DSP buys for
 */
public record DspProfile(
        String name,
        double bidRate,
        double cpmMedian,
        double cpmSigma,
        double latencyMedianMs,
        double latencySigma,
        double firstPriceShade,
        List<String> advertisers) {

    /** Three DSPs with deliberately different trade-offs, to make the auction interesting. */
    public static final Map<String, DspProfile> PRESETS = Map.of(
            // Selective, high value, reliably fast.
            "premium", new DspProfile("premium-dsp", 0.55, 34.0, 0.25, 35, 0.30, 0.85,
                    List.of("auroraluxe.example", "velvetline.example", "summitwatch.example")),
            // Bids on almost everything at modest prices.
            "budget", new DspProfile("budget-dsp", 0.90, 17.0, 0.35, 22, 0.25, 0.90,
                    List.of("snackbox.example", "quickcart.example", "playful.example", "brightsoda.example")),
            // Values impressions highly but is slow: a share of its bids misses the deadline.
            "slow", new DspProfile("slow-dsp", 0.75, 40.0, 0.30, 85, 0.45, 0.85,
                    List.of("roadster.example", "voltcar.example")));
}
