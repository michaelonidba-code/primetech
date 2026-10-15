package dev.primetech.ads.adserver;

import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.pacing.PacingPeriod;
import dev.primetech.ads.common.pacing.PacingState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Pacing state from Redis, refreshed in the background once a second so requests never wait on it.
 *
 * <p>Served spend is counted in Redis (shared by every ad server instance) and locally. Between
 * refreshes, an instance adds its own increments to the last snapshot, so it notices its own
 * spending immediately; another instance's spending shows up within a second.
 *
 * <p>If pacing state is missing or stale (pacing service down), each line item falls back to a
 * small fixed throttle and a hard stop once its served spend reaches its budget. Every delivered
 * impression was served first, so that stop cannot overspend, whatever share of served ads goes
 * unplayed. Under-delivering for a while is recoverable; overspending a client's budget is not.
 */
public final class RedisPacingView implements PacingView, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RedisPacingView.class);
    static final Duration STALE_AFTER = Duration.ofSeconds(60);

    private final JedisPooled redis;
    private final PacingPeriod period;
    private final Map<String, LineItemConfig> configs;
    private final double fallbackThrottle;
    private final LongSupplier clock;
    private final Counter fallbacks;
    private final Counter errors;

    private volatile Map<String, PacingState> states = Map.of();
    private volatile Map<String, Long> servedSnapshot = Map.of();
    private volatile long snapshotPeriod = Long.MIN_VALUE;
    /** This instance's served spend per line item and period, monotonic. */
    private final Map<String, AtomicLong> localServed = new ConcurrentHashMap<>();
    /** {@link #localServed} as it was just before the last snapshot was read. */
    private volatile Map<String, Long> localAtSnapshot = Map.of();
    private final Thread refresher;

    public RedisPacingView(String url, PacingPeriod period, Collection<LineItemConfig> lineItems, double fallbackThrottle,
                           LongSupplier clock, MeterRegistry registry) {
        URI uri = URI.create(url);
        var pool = new GenericObjectPoolConfig<Connection>();
        pool.setMaxTotal(16);
        pool.setMaxWait(Duration.ofMillis(20));
        this.redis = new JedisPooled(pool, new HostAndPort(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379),
                DefaultJedisClientConfig.builder().connectionTimeoutMillis(200).socketTimeoutMillis(200).build());
        this.period = period;
        var byId = new HashMap<String, LineItemConfig>();
        lineItems.forEach(l -> byId.put(l.lineItemId(), l));
        this.configs = Map.copyOf(byId);
        this.fallbackThrottle = fallbackThrottle;
        this.clock = clock;
        this.fallbacks = registry.counter("adserver.pacing.fallback");
        this.errors = registry.counter("adserver.pacing.errors");
        refresh();
        this.refresher = Thread.ofPlatform().name("pacing-refresh").daemon().start(this::refreshLoop);
    }

    @Override
    public double throttle(String lineItemId) {
        LineItemConfig config = configs.get(lineItemId);
        if (config == null) {
            return 0;
        }
        long now = clock.getAsLong();
        long periodIndex = period.index(now);
        PacingState s = states.get(lineItemId);
        double throttle;
        long stopAt;
        if (s == null || s.periodIndex() != periodIndex || now - s.updatedAtMs() > STALE_AFTER.toMillis()) {
            fallbacks.increment();
            throttle = fallbackThrottle;
            stopAt = config.periodBudgetMicros();
        } else {
            throttle = s.throttle();
            stopAt = s.stopAtServedMicros();
        }
        return servedNow(lineItemId, periodIndex) >= stopAt ? 0 : throttle;
    }

    @Override
    public void served(String lineItemId, long priceMicros) {
        long periodIndex = period.index(clock.getAsLong());
        String key = PacingState.servedKey(periodIndex, lineItemId);
        localServed.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(priceMicros);
        try {
            try (var p = redis.pipelined()) {
                p.incrBy(key, priceMicros);
                p.expire(key, period.lengthMs() / 1000 * 2);
                p.sync();
            }
        } catch (RuntimeException e) {
            errors.increment();
        }
    }

    /** Last shared total plus what this instance served since it was read. */
    long servedNow(String lineItemId, long periodIndex) {
        String key = PacingState.servedKey(periodIndex, lineItemId);
        AtomicLong mine = localServed.get(key);
        long local = mine == null ? 0 : mine.get();
        if (snapshotPeriod != periodIndex) {
            return local;
        }
        return servedSnapshot.getOrDefault(lineItemId, 0L) + local - localAtSnapshot.getOrDefault(key, 0L);
    }

    private void refreshLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                return;
            }
            refresh();
        }
    }

    void refresh() {
        try {
            var parsed = new HashMap<String, PacingState>();
            for (var e : redis.hgetAll(PacingState.REDIS_HASH).entrySet()) {
                try {
                    parsed.put(e.getKey(), Json.MAPPER.readValue(e.getValue(), PacingState.class));
                } catch (IOException ignored) {
                    // one bad field must not hide the others
                }
            }
            long periodIndex = period.index(clock.getAsLong());
            List<String> ids = new ArrayList<>(configs.keySet());
            // Captured before reading Redis: increments in between are counted twice (stopping a
            // little early) rather than not at all.
            var localBefore = new HashMap<String, Long>();
            localServed.forEach((k, v) -> localBefore.put(k, v.get()));
            List<String> values = redis.mget(ids.stream().map(id -> PacingState.servedKey(periodIndex, id)).toArray(String[]::new));
            var served = new HashMap<String, Long>();
            for (int i = 0; i < ids.size(); i++) {
                served.put(ids.get(i), values.get(i) == null ? 0L : Long.parseLong(values.get(i)));
            }
            states = parsed;
            localAtSnapshot = localBefore;
            servedSnapshot = served;
            snapshotPeriod = periodIndex;
            localServed.keySet().removeIf(k -> !k.startsWith("pacing:served:" + periodIndex + ":"));
        } catch (RuntimeException e) {
            errors.increment();
            log.debug("pacing refresh failed: {}", e.toString());
        }
    }

    /** For the stats page: how many line items have fresh state, and how many are spent. */
    Map<String, Object> summary() {
        long now = clock.getAsLong();
        long periodIndex = period.index(now);
        int fresh = 0, exhausted = 0;
        for (PacingState s : states.values()) {
            if (s.periodIndex() == periodIndex && now - s.updatedAtMs() <= STALE_AFTER.toMillis()) {
                fresh++;
                if (s.status() == PacingState.Status.EXHAUSTED) {
                    exhausted++;
                }
            }
        }
        return Map.of("lineItems", configs.size(), "fresh", fresh, "exhausted", exhausted);
    }

    @Override
    public void close() {
        refresher.interrupt();
        redis.close();
    }
}
