package dev.primetech.ads.common.pacing;

import redis.clients.jedis.Response;
import redis.clients.jedis.UnifiedJedis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Served spend per period and line item, as kept in Redis by the ad servers.
 *
 * <p>Each line item has a hash ({@link PacingState#servedKey}) with one field per ad server
 * instance, holding that instance's cumulative served spend for the period. Instances write their
 * absolute total ({@code HSET}), never an increment, so a write that fails, times out or is retried
 * can neither lose nor double-count spend. The line item's served spend is the sum of the fields.
 */
public final class ServedSpend {

    /** Sums every instance's field for each line item; line items with no hash are absent. */
    public static Map<String, Long> read(UnifiedJedis redis, long periodIndex, Collection<String> lineItemIds) {
        var out = new HashMap<String, Long>();
        if (lineItemIds.isEmpty()) {
            return out;
        }
        List<String> ids = new ArrayList<>(lineItemIds);
        var responses = new ArrayList<Response<List<String>>>(ids.size());
        try (var pipeline = redis.pipelined()) {
            for (String id : ids) {
                responses.add(pipeline.hvals(PacingState.servedKey(periodIndex, id)));
            }
            pipeline.sync();
        }
        for (int i = 0; i < ids.size(); i++) {
            List<String> values = responses.get(i).get();
            if (values != null && !values.isEmpty()) {
                out.put(ids.get(i), values.stream().mapToLong(Long::parseLong).sum());
            }
        }
        return out;
    }

    private ServedSpend() {
    }
}
