package dev.primetech.ads.adserver;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This ad server instance's served spend per period and line item, and how it combines with the
 * shared totals in Redis.
 *
 * <p>Requests only add to an in-memory total ({@link #add}); a background thread writes each
 * total to Redis as an absolute value and reads everyone's totals back. Served spend as seen by
 * this instance is the shared total minus what Redis holds for this instance, plus this
 * instance's own up-to-date total. So it never lags this instance's own spending, it counts other
 * instances' spending within a refresh, and a failed write loses nothing: the next write carries
 * the full total.
 *
 * <p>The first version incremented Redis on every request and gave up after 20 ms waiting for a
 * connection. While the stack warmed up, 4,744 increments were dropped in three minutes, and the
 * audit found every line item's counter 3–6% low and 21 line items over budget.
 */
final class ServedLedger {
    /** Keyed by {@code PacingState.servedKey(period, lineItemId)}; monotonic. */
    private final Map<String, AtomicLong> mine = new ConcurrentHashMap<>();
    /** What Redis holds for this instance, per key: the last value written successfully. */
    private final Map<String, Long> written = new ConcurrentHashMap<>();

    private volatile Snapshot snapshot = new Snapshot(Long.MIN_VALUE, Map.of(), Map.of());

    /** Shared totals per line item for one period, and this instance's share of them at the time. */
    private record Snapshot(long periodIndex, Map<String, Long> totals, Map<String, Long> writtenAtRead) {
    }

    void add(String key, long micros) {
        mine.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(micros);
    }

    /** Totals that differ from what Redis holds for this instance: what the next write should send. */
    Map<String, Long> unwritten() {
        var out = new HashMap<String, Long>();
        mine.forEach((key, total) -> {
            long value = total.get();
            if (value != written.getOrDefault(key, 0L)) {
                out.put(key, value);
            }
        });
        return out;
    }

    /** Records a successful write of these absolute totals. */
    void written(Map<String, Long> values) {
        written.putAll(values);
    }

    /**
     * Installs totals read from Redis. Must be called by the same thread that writes, right after
     * reading, so {@code written} is exactly what Redis held for this instance during the read.
     */
    void snapshot(long periodIndex, Map<String, Long> totalsByLineItem, String keyPrefix) {
        var share = new HashMap<String, Long>();
        written.forEach((key, value) -> {
            if (key.startsWith(keyPrefix)) {
                share.put(key.substring(keyPrefix.length()), value);
            }
        });
        snapshot = new Snapshot(periodIndex, Map.copyOf(totalsByLineItem), share);
    }

    /** Served spend for a line item in a period, all instances included. */
    long served(long periodIndex, String lineItemId, String key) {
        AtomicLong own = mine.get(key);
        long ownNow = own == null ? 0 : own.get();
        Snapshot s = snapshot;
        if (s.periodIndex() != periodIndex) {
            return ownNow;
        }
        return s.totals().getOrDefault(lineItemId, 0L) - s.writtenAtRead().getOrDefault(lineItemId, 0L) + ownNow;
    }

    /** Drops keys of finished periods once they have been written. */
    void prune(String currentKeyPrefix) {
        mine.entrySet().removeIf(e -> !e.getKey().startsWith(currentKeyPrefix)
                && e.getValue().get() == written.getOrDefault(e.getKey(), 0L));
        written.keySet().removeIf(k -> !k.startsWith(currentKeyPrefix) && !mine.containsKey(k));
    }

    long unwrittenMicros() {
        long sum = 0;
        for (var e : mine.entrySet()) {
            sum += e.getValue().get() - written.getOrDefault(e.getKey(), 0L);
        }
        return sum;
    }
}
