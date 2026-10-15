package dev.primetech.ads.adserver;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.time.Duration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Frequency caps in Redis: one counter per device, campaign and hour, so every ad server instance
 * shares them. Reading is one MGET per request; writing is one pipelined INCR + EXPIRE per win.
 *
 * <p>Tight timeouts on purpose: a cap check is worth a few milliseconds, not the request. When
 * Redis is slow or the pool is busy, calls fail fast and the caller serves uncapped.
 */
public final class RedisFrequencyCaps implements FrequencyCaps, AutoCloseable {
    private static final long KEY_TTL_SECONDS = 2 * 3600;

    private final JedisPooled redis;

    public RedisFrequencyCaps(String url) {
        URI uri = URI.create(url);
        var pool = new GenericObjectPoolConfig<Connection>();
        pool.setMaxTotal(32);
        pool.setMaxIdle(32);
        pool.setMinIdle(8);
        pool.setMaxWait(Duration.ofMillis(20));
        var client = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(200)
                .socketTimeoutMillis(50)
                .build();
        this.redis = new JedisPooled(pool, new HostAndPort(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379), client);
    }

    static String key(String deviceIdHash, String campaignId, long hour) {
        return "fc:" + deviceIdHash + ":" + campaignId + ":" + hour;
    }

    @Override
    public Map<String, Long> counts(String deviceIdHash, Collection<String> campaignIds) {
        if (campaignIds.isEmpty()) {
            return Map.of();
        }
        long hour = FrequencyCaps.currentHour();
        List<String> ids = new ArrayList<>(campaignIds);
        String[] keys = ids.stream().map(c -> key(deviceIdHash, c, hour)).toArray(String[]::new);
        List<String> values = redis.mget(keys);
        var counts = new HashMap<String, Long>();
        for (int i = 0; i < ids.size(); i++) {
            if (values.get(i) != null) {
                counts.put(ids.get(i), Long.parseLong(values.get(i)));
            }
        }
        return counts;
    }

    @Override
    public void record(String deviceIdHash, Collection<String> campaignIds) {
        long hour = FrequencyCaps.currentHour();
        try (var pipeline = redis.pipelined()) {
            for (String campaignId : campaignIds) {
                String key = key(deviceIdHash, campaignId, hour);
                pipeline.incr(key);
                pipeline.expire(key, KEY_TTL_SECONDS);
            }
            pipeline.sync();
        }
    }

    @Override
    public void close() {
        redis.close();
    }
}
