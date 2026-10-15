package dev.primetech.ads.common.catalog;

import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.DeviceType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Synthetic direct-sold campaigns, line items and creatives: the stand-in for a campaign
 * management system. Ids are deterministic for a given seed. Traffic weights follow a power law,
 * like real campaigns where a few large advertisers take most impressions.
 */
public final class AdCatalog {

    /**
     * One creative of a line item, with the line item's terms and targeting.
     *
     * @param countries           ISO alpha-2 countries the line item targets; empty means all
     * @param deviceTypes         device types it targets; empty means all
     * @param frequencyCapPerHour most impressions of the campaign one device may see per hour
     */
    public record AdSpec(String adId, String creativeId, String campaignId, String lineItemId, String advertiserId,
                         long cpmMicros, int durationSeconds, Set<String> countries, Set<DeviceType> deviceTypes,
                         int frequencyCapPerHour) {
        /** Price of one impression: CPM is per thousand. */
        public long impressionPriceMicros() {
            return cpmMicros / 1000;
        }

        public boolean targets(String country, DeviceType deviceType) {
            return (countries.isEmpty() || countries.contains(country))
                    && (deviceTypes.isEmpty() || deviceTypes.contains(deviceType));
        }
    }

    private static final String[] ADVERTISERS = {
            "Brightline Auto", "Harbor Bank", "Juniper Foods", "Northpeak Outdoors", "Lumen Mobile",
            "Copperleaf Home", "Tidewater Travel", "Orbit Games", "Fernway Pharmacy", "Summit Insurance",
            "Kestrel Apparel", "Bluestem Grocers"};
    private static final String[] OBJECTIVES = {"Awareness", "Launch", "Seasonal", "Retargeting", "Brand Lift"};

    private final List<AdSpec> ads;
    private final List<CampaignMetadata> campaigns;
    private final double[] cumulativeWeights;

    private AdCatalog(List<AdSpec> ads, List<CampaignMetadata> campaigns, double[] cumulativeWeights) {
        this.ads = ads;
        this.campaigns = campaigns;
        this.cumulativeWeights = cumulativeWeights;
    }

    public static AdCatalog generate(int campaigns, RandomGenerator random) {
        var ads = new ArrayList<AdSpec>();
        var metadata = new ArrayList<CampaignMetadata>();
        var weights = new ArrayList<Double>();
        for (int c = 0; c < campaigns; c++) {
            String campaignId = "camp-%04d".formatted(c);
            int advertiser = c % ADVERTISERS.length;
            metadata.add(new CampaignMetadata(campaignId,
                    "%s %s %d".formatted(ADVERTISERS[advertiser], OBJECTIVES[c % OBJECTIVES.length], c / ADVERTISERS.length + 1),
                    "adv-%02d".formatted(advertiser), ADVERTISERS[advertiser]));
            double campaignWeight = 1.0 / Math.pow(c + 1, 0.8);
            String advertiserId = "adv-%02d".formatted(advertiser);
            int lineItems = 1 + random.nextInt(3);
            for (int l = 0; l < lineItems; l++) {
                String lineItemId = "%s-li-%d".formatted(campaignId, l);
                // CTV CPMs typically sit in the $20-$60 range.
                long cpmMicros = (20 + random.nextInt(41)) * 1_000_000L;
                int creatives = 1 + random.nextInt(2);
                // Some line items are narrowly targeted, as real direct deals often are.
                Set<String> countries = random.nextDouble() < 0.3 ? Set.of("US") : Set.of();
                Set<DeviceType> devices = random.nextDouble() < 0.2 ? EnumSet.of(DeviceType.CTV) : EnumSet.noneOf(DeviceType.class);
                int frequencyCap = 2 + random.nextInt(3);
                for (int k = 0; k < creatives; k++) {
                    String creativeId = "%s-cr-%d".formatted(lineItemId, k);
                    String adId = "ad-%s".formatted(creativeId.substring("camp-".length()));
                    int duration = random.nextBoolean() ? 15 : 30;
                    ads.add(new AdSpec(adId, creativeId, campaignId, lineItemId, advertiserId, cpmMicros, duration,
                            countries, devices, frequencyCap));
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
        return new AdCatalog(List.copyOf(ads), List.copyOf(metadata), cumulative);
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

    public List<CampaignMetadata> campaigns() {
        return campaigns;
    }
}
