package dev.primetech.ads.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Synthetic campaigns, line items and creatives. Ids are deterministic so later pipeline stages
 * can rebuild the same catalog for enrichment. Traffic follows a power law, like real campaigns
 * where a few large advertisers take most impressions.
 */
public final class AdCatalog {

    public record AdSpec(String adId, String creativeId, String campaignId, String lineItemId,
                         long cpmMicros, int durationSeconds) {
        /** Price of one impression: CPM is per thousand. */
        public long impressionPriceMicros() {
            return cpmMicros / 1000;
        }
    }

    private final List<AdSpec> ads;
    private final double[] cumulativeWeights;

    private AdCatalog(List<AdSpec> ads, double[] cumulativeWeights) {
        this.ads = ads;
        this.cumulativeWeights = cumulativeWeights;
    }

    public static AdCatalog generate(int campaigns, RandomGenerator random) {
        var ads = new ArrayList<AdSpec>();
        var weights = new ArrayList<Double>();
        for (int c = 0; c < campaigns; c++) {
            String campaignId = "camp-%04d".formatted(c);
            double campaignWeight = 1.0 / Math.pow(c + 1, 0.8);
            int lineItems = 1 + random.nextInt(3);
            for (int l = 0; l < lineItems; l++) {
                String lineItemId = "%s-li-%d".formatted(campaignId, l);
                // CTV CPMs typically sit in the $20-$60 range.
                long cpmMicros = (20 + random.nextInt(41)) * 1_000_000L;
                int creatives = 1 + random.nextInt(2);
                for (int k = 0; k < creatives; k++) {
                    String creativeId = "%s-cr-%d".formatted(lineItemId, k);
                    String adId = "ad-%s".formatted(creativeId.substring("camp-".length()));
                    int duration = random.nextBoolean() ? 15 : 30;
                    ads.add(new AdSpec(adId, creativeId, campaignId, lineItemId, cpmMicros, duration));
                    weights.add(campaignWeight / (lineItems * creatives));
                }
            }
        }
        double[] cumulative = new double[weights.size()];
        double sum = 0;
        for (int i = 0; i < cumulative.length; i++) {
            sum += weights.get(i);
            cumulative[i] = sum;
        }
        return new AdCatalog(List.copyOf(ads), cumulative);
    }

    public AdSpec pick(RandomGenerator random) {
        double r = random.nextDouble() * cumulativeWeights[cumulativeWeights.length - 1];
        int lo = 0, hi = cumulativeWeights.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulativeWeights[mid] < r) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return ads.get(lo);
    }

    public List<AdSpec> ads() {
        return ads;
    }
}
