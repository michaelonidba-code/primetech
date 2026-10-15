package dev.primetech.ads.simulator;

import dev.primetech.ads.common.DeviceType;
import dev.primetech.ads.common.EventType;

import java.util.ArrayList;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Plans realistic ad breaks: a pod of 1-3 ads played back to back, each firing an impression then
 * quartiles until the viewer drops off, plus an occasional click.
 */
final class SessionPlanner {
    /** Chance the viewer reaches each next quartile given they reached the previous one. */
    static final double QUARTILE_CONTINUE = 0.97;
    static final double CLICK_RATE = 0.004;

    private static final EventType[] QUARTILES = {
            EventType.FIRST_QUARTILE, EventType.MIDPOINT, EventType.THIRD_QUARTILE, EventType.COMPLETE};
    private static final DeviceType[] DEVICE_TYPES = DeviceType.values();
    private static final double[] DEVICE_WEIGHTS = {0.70, 0.15, 0.10, 0.05};
    private static final String[] COUNTRIES = {"US", "CA", "GB", "DE", "FR", "BR", "MX", "JP", "KR", "AU"};
    private static final double[] COUNTRY_WEIGHTS = {0.45, 0.06, 0.08, 0.06, 0.05, 0.08, 0.06, 0.06, 0.05, 0.05};
    private static final double CONSENT_RATE = 0.85;

    private final AdCatalog catalog;

    SessionPlanner(AdCatalog catalog) {
        this.catalog = catalog;
    }

    Session plan(long startMs, RandomGenerator random) {
        var ads = new ArrayList<AdCatalog.AdSpec>();
        var types = new ArrayList<EventType>();
        var due = new ArrayList<Long>();

        int podSize = 1 + random.nextInt(3);
        long adStart = startMs;
        boolean viewerLeft = false;
        for (int a = 0; a < podSize && !viewerLeft; a++) {
            AdCatalog.AdSpec ad = catalog.pick(random);
            long durationMs = ad.durationSeconds() * 1000L;
            ads.add(ad);
            types.add(EventType.IMPRESSION);
            due.add(adStart);

            int reached = 0;
            while (reached < QUARTILES.length && random.nextDouble() < QUARTILE_CONTINUE) {
                reached++;
                ads.add(ad);
                types.add(QUARTILES[reached - 1]);
                due.add(adStart + durationMs * reached / 4);
            }
            if (random.nextDouble() < CLICK_RATE) {
                long clickAt = adStart + (long) (random.nextDouble() * durationMs * reached / 4);
                insertSorted(ads, types, due, ad, EventType.CLICK, clickAt);
            }
            // A viewer who abandons an ad leaves the break; no more ads in this pod.
            viewerLeft = reached < QUARTILES.length;
            adStart += durationMs;
        }

        long[] dueArr = due.stream().mapToLong(Long::longValue).toArray();
        return new Session(
                new UUID(random.nextLong(), random.nextLong()).toString(),
                "dev-" + Long.toHexString(random.nextLong()),
                DEVICE_TYPES[weighted(DEVICE_WEIGHTS, random)],
                COUNTRIES[weighted(COUNTRY_WEIGHTS, random)],
                random.nextDouble() < CONSENT_RATE,
                false,
                ads.toArray(AdCatalog.AdSpec[]::new),
                types.toArray(EventType[]::new),
                dueArr);
    }

    /**
     * A bot session: a pod of three ads, each an impression followed half a second later by a
     * "complete" (no real player finishes a 15-30s ad that fast), always from the same device.
     * Bots claim consent, so their hashed device id is visible to invalid-traffic detection.
     */
    Session planBot(long startMs, String botDeviceId, RandomGenerator random) {
        int ads = 3;
        var specs = new AdCatalog.AdSpec[ads * 2];
        var types = new EventType[ads * 2];
        var due = new long[ads * 2];
        for (int a = 0; a < ads; a++) {
            AdCatalog.AdSpec ad = catalog.pick(random);
            specs[2 * a] = ad;
            specs[2 * a + 1] = ad;
            types[2 * a] = EventType.IMPRESSION;
            types[2 * a + 1] = EventType.COMPLETE;
            due[2 * a] = startMs + a * 1_000L;
            due[2 * a + 1] = startMs + a * 1_000L + 500;
        }
        return new Session(new UUID(random.nextLong(), random.nextLong()).toString(), botDeviceId,
                DeviceType.CTV, "US", true, true, specs, types, due);
    }

    /** Average events per session, measured by sampling, so the engine can hit a target event rate. */
    double estimateEventsPerSession(RandomGenerator random, int samples) {
        long total = 0;
        for (int i = 0; i < samples; i++) {
            total += plan(0, random).size();
        }
        return (double) total / samples;
    }

    private static void insertSorted(ArrayList<AdCatalog.AdSpec> ads, ArrayList<EventType> types,
                                     ArrayList<Long> due, AdCatalog.AdSpec ad, EventType type, long at) {
        int i = due.size();
        while (i > 0 && due.get(i - 1) > at) {
            i--;
        }
        ads.add(i, ad);
        types.add(i, type);
        due.add(i, at);
    }

    private static int weighted(double[] weights, RandomGenerator random) {
        double r = random.nextDouble();
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }
}
