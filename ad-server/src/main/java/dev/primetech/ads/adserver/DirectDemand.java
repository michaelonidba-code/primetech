package dev.primetech.ads.adserver;

import dev.primetech.ads.common.DeviceType;
import dev.primetech.ads.common.catalog.AdCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Direct-sold line items from the campaign catalog, filtered by targeting.
 *
 * <p>Until budget pacing exists (Phase 4), each line item enters a given auction only with
 * probability {@code participation}. Without some throttle, every line item would bid on every
 * request at its full CPM and the best of ~100 direct prices would beat programmatic demand
 * almost every time; real direct deals are limited by budgets and delivery goals.
 */
public final class DirectDemand {
    private final Map<String, List<AdCatalog.AdSpec>> byLineItem = new HashMap<>();
    private final double participation;

    public DirectDemand(AdCatalog catalog, double participation) {
        for (AdCatalog.AdSpec ad : catalog.ads()) {
            byLineItem.computeIfAbsent(ad.lineItemId(), k -> new ArrayList<>()).add(ad);
        }
        this.participation = participation;
    }

    public List<Candidate> eligible(String country, DeviceType deviceType, RandomGenerator random) {
        var out = new ArrayList<Candidate>();
        for (List<AdCatalog.AdSpec> creatives : byLineItem.values()) {
            if (!creatives.get(0).targets(country, deviceType)
                    || (participation < 1 && random.nextDouble() >= participation)) {
                continue;
            }
            for (AdCatalog.AdSpec ad : creatives) {
                out.add(new Candidate(Candidate.DIRECT, null, ad.campaignId(), ad.lineItemId(), ad.adId(),
                        ad.creativeId(), ad.advertiserId(), ad.durationSeconds(), ad.cpmMicros(),
                        ad.frequencyCapPerHour(), null));
            }
        }
        return out;
    }
}
