package dev.primetech.ads.pacing;

import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.pacing.PacingState;
import dev.primetech.ads.common.pacing.ServedSpend;
import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pacing state in one Redis hash, one field per line item, written in a single HSET per tick. */
public final class RedisPacingStore implements PacingStore, AutoCloseable {
    private final JedisPooled redis;

    public RedisPacingStore(String url) {
        this.redis = new JedisPooled(URI.create(url));
    }

    @Override
    public Map<String, Long> served(long periodIndex, Collection<String> lineItemIds) {
        var out = new HashMap<String, Long>();
        lineItemIds.forEach(id -> out.put(id, 0L));
        out.putAll(ServedSpend.read(redis, periodIndex, lineItemIds));
        return out;
    }

    @Override
    public void publish(List<PacingState> states) throws IOException {
        if (states.isEmpty()) {
            return;
        }
        var fields = new HashMap<String, String>();
        for (PacingState s : states) {
            fields.put(s.lineItemId(), Json.MAPPER.writeValueAsString(s));
        }
        redis.hset(PacingState.REDIS_HASH, fields);
    }

    @Override
    public void close() {
        redis.close();
    }
}
