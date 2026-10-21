package dev.primetech.ads.adserver;

import dev.primetech.ads.common.DeviceType;
import dev.primetech.ads.common.catalog.AdCatalog;
import dev.primetech.ads.common.catalog.LineItemConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Direct-sold line items from the campaign catalog, filtered by targeting and by pacing: each line
 * item enters an auction with the probability its pacer sets, and not at all once its budget is
 * spent.
 */
public final class DirectDemand {
    private record State(Map<String, List<AdCatalog.AdSpec>> byLineItem, Map<String, LineItemConfig.Tier> tiers) {
    }

    private volatile State state;
    private final PacingView pacing;

    public DirectDemand(AdCatalog catalog, PacingView pacing) {
        this.pacing = pacing;
        update(catalog);
    }

    /** Swaps in a new catalog (sponsor campaigns changed). In-flight auctions keep the one they started with. */
    public void update(AdCatalog catalog) {
        var byLineItem = new HashMap<String, List<AdCatalog.AdSpec>>();
        for (AdCatalog.AdSpec ad : catalog.ads()) {
            byLineItem.computeIfAbsent(ad.lineItemId(), k -> new ArrayList<>()).add(ad);
        }
        var tiers = new HashMap<String, LineItemConfig.Tier>();
        catalog.lineItems().forEach(l -> tiers.put(l.lineItemId(), l.tier()));
        state = new State(byLineItem, tiers);
    }

    public List<Candidate> eligible(String country, DeviceType deviceType, RandomGenerator random) {
        var out = new ArrayList<Candidate>();
        State current = state;
        for (List<AdCatalog.AdSpec> creatives : current.byLineItem().values()) {
            AdCatalog.AdSpec first = creatives.get(0);
            if (!first.targets(country, deviceType)) {
                continue;
            }
            double throttle = pacing.throttle(first.lineItemId());
            if (throttle <= 0 || (throttle < 1 && random.nextDouble() >= throttle)) {
                continue;
            }
            boolean guaranteed = current.tiers().get(first.lineItemId()) == LineItemConfig.Tier.GUARANTEED;
            for (AdCatalog.AdSpec ad : creatives) {
                out.add(new Candidate(Candidate.DIRECT, null, ad.campaignId(), ad.lineItemId(), ad.adId(),
                        ad.creativeId(), ad.advertiserId(), ad.durationSeconds(), ad.cpmMicros(),
                        ad.frequencyCapPerHour(), null, guaranteed));
            }
        }
        return out;
    }

    /** Charges a served direct ad to its line item's budget. */
    public void served(String lineItemId, long priceMicros) {
        pacing.served(lineItemId, priceMicros);
    }
}
