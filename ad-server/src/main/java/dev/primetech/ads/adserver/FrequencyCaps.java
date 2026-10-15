package dev.primetech.ads.adserver;

import java.util.Collection;
import java.util.Map;

/**
 * Per device, per campaign impression counts in fixed clock-hour windows. Counting happens when an
 * ad is served, not when the player reports the impression: simpler and immediate, at the cost of
 * counting the occasional served-but-not-played ad.
 */
public interface FrequencyCaps {

    /** Impressions this hour for each campaign; campaigns never served are absent or zero. */
    Map<String, Long> counts(String deviceIdHash, Collection<String> campaignIds) throws Exception;

    void record(String deviceIdHash, Collection<String> campaignIds) throws Exception;

    static long currentHour() {
        return System.currentTimeMillis() / 3_600_000L;
    }
}
