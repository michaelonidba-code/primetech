package dev.primetech.ads.pacing;

import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.pacing.PacingState;
import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
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
        if (lineItemIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>(lineItemIds);
        List<String> values = redis.mget(ids.stream().map(id -> PacingState.servedKey(periodIndex, id)).toArray(String[]::new));
        var out = new HashMap<String, Long>();
        for (int i = 0; i < ids.size(); i++) {
            out.put(ids.get(i), values.get(i) == null ? 0L : Long.parseLong(values.get(i)));
        }
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
