package dev.primetech.ads.adserver;

import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.pacing.PacingPeriod;
import dev.primetech.ads.common.pacing.PacingState;
import dev.primetech.ads.common.pacing.ServedSpend;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Pacing state from Redis, refreshed in the background once a second so requests never wait on it.
 *
 * <p>Served spend is shared through Redis without touching Redis on the request path: requests add
 * to this instance's totals ({@link ServedLedger}), and the background thread writes them as
 * absolute values every 250 ms and reads every instance's totals every second. An instance sees
 * its own spending immediately and another instance's within about a second.
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
    private volatile Map<String, LineItemConfig> configs;
    private final double fallbackThrottle;
    private final LongSupplier clock;
    private final Counter fallbacks;
    private final Counter errors;

    static final Duration WRITE_EVERY = Duration.ofMillis(250);
    static final Duration READ_EVERY = Duration.ofSeconds(1);

    private volatile Map<String, PacingState> states = Map.of();
    private final ServedLedger ledger = new ServedLedger();
    /** This instance's field in each served-spend hash: unique per process start. */
    private final String instanceId;
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
        this.instanceId = hostName() + "-" + Long.toHexString(System.nanoTime() ^ ProcessHandle.current().pid() << 32);
        registry.gauge("adserver.served.unwritten.micros", ledger, ServedLedger::unwrittenMicros);
        refresh();
        this.refresher = Thread.ofPlatform().name("pacing-refresh").daemon().start(this::refreshLoop);
    }

    @Override
    public void lineItems(Collection<LineItemConfig> lineItems) {
        var byId = new HashMap<String, LineItemConfig>();
        lineItems.forEach(l -> byId.put(l.lineItemId(), l));
        configs = Map.copyOf(byId);
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

    /** Never touches Redis: the background thread writes the totals. */
    @Override
    public void served(String lineItemId, long priceMicros) {
        ledger.add(PacingState.servedKey(period.index(clock.getAsLong()), lineItemId), priceMicros);
    }

    long servedNow(String lineItemId, long periodIndex) {
        return ledger.served(periodIndex, lineItemId, PacingState.servedKey(periodIndex, lineItemId));
    }

    private void refreshLoop() {
        long lastRead = System.nanoTime();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(WRITE_EVERY.toMillis());
            } catch (InterruptedException e) {
                flush(); // what was served last must not be lost on shutdown
                return;
            }
            flush();
            if (System.nanoTime() - lastRead >= READ_EVERY.toNanos()) {
                refresh();
                lastRead = System.nanoTime();
            }
        }
    }

    /** Writes this instance's totals as absolute values: retrying after a failure is always safe. */
    void flush() {
        Map<String, Long> totals = ledger.unwritten();
        if (totals.isEmpty()) {
            return;
        }
        try (var p = redis.pipelined()) {
            long ttlSeconds = period.lengthMs() / 1000 * 2;
            totals.forEach((key, value) -> {
                p.hset(key, instanceId, Long.toString(value));
                p.expire(key, ttlSeconds);
            });
            p.sync();
            ledger.written(totals);
        } catch (RuntimeException e) {
            errors.increment(); // kept in the ledger; the next write carries the full totals
        }
    }

    /** Reads pacing state and every instance's served totals. Runs on the writing thread, right after a write. */
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
            Map<String, Long> totals = ServedSpend.read(redis, periodIndex, configs.keySet());
            states = parsed;
            String prefix = PacingState.servedKey(periodIndex, "");
            ledger.snapshot(periodIndex, totals, prefix);
            ledger.prune(prefix);
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
        try {
            refresher.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        redis.close();
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException e) {
            return "ad-server";
        }
    }
}
