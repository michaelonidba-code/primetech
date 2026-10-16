package dev.primetech.ads.adserver;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance frequency caps, for tests and for running without Redis. */
public final class InMemoryFrequencyCaps implements FrequencyCaps {
    private final Map<String, Long> counters = new ConcurrentHashMap<>();

    @Override
    public Map<String, Long> counts(String deviceIdHash, Collection<String> campaignIds) {
        long hour = FrequencyCaps.currentHour();
        var out = new HashMap<String, Long>();
        for (String c : campaignIds) {
            Long n = counters.get(RedisFrequencyCaps.key(deviceIdHash, c, hour));
            if (n != null) {
                out.put(c, n);
            }
        }
        return out;
    }

    @Override
    public void record(String deviceIdHash, Collection<String> campaignIds) {
        long hour = FrequencyCaps.currentHour();
        for (String c : campaignIds) {
            counters.merge(RedisFrequencyCaps.key(deviceIdHash, c, hour), 1L, Long::sum);
        }
    }
}
