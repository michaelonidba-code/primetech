package dev.primetech.ads.simulator;

import dev.primetech.ads.common.DeviceType;
import dev.primetech.ads.common.EventType;
import dev.primetech.ads.common.catalog.AdCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Plans realistic ad breaks: a pod of ads played back to back, each firing an impression then
 * quartiles until the viewer drops off, plus an occasional click. The pod comes either from the
 * local catalog or from an ad server response.
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
    static final int BOT_POD_SIZE = 3;

    /** Who is watching: decided before the ad request, because the ad server targets on it. */
    record Viewer(String sessionId, String deviceId, DeviceType deviceType, String country, boolean consent,
                  boolean bot, int slots) {
    }

    /** One ad to play, with what the player reports on its tracking events. */
    record PodAd(String adId, String creativeId, String campaignId, String lineItemId, int durationSeconds,
                 long priceMicros, String auctionId) {

        static PodAd of(AdCatalog.AdSpec spec) {
            return new PodAd(spec.adId(), spec.creativeId(), spec.campaignId(), spec.lineItemId(),
                    spec.durationSeconds(), spec.impressionPriceMicros(), null);
        }
    }

    private final AdCatalog catalog;

    SessionPlanner(AdCatalog catalog) {
        this.catalog = catalog;
    }

    Viewer viewer(RandomGenerator random) {
        return new Viewer(
                new UUID(random.nextLong(), random.nextLong()).toString(),
                "dev-" + Long.toHexString(random.nextLong()),
                DEVICE_TYPES[weighted(DEVICE_WEIGHTS, random)],
                COUNTRIES[weighted(COUNTRY_WEIGHTS, random)],
                random.nextDouble() < CONSENT_RATE,
                false,
                1 + random.nextInt(3));
    }

    /** Bots claim consent, so their hashed device id is visible to invalid-traffic detection. */
    Viewer botViewer(String botDeviceId, RandomGenerator random) {
        return new Viewer(new UUID(random.nextLong(), random.nextLong()).toString(), botDeviceId, DeviceType.CTV, "US",
                true, true, BOT_POD_SIZE);
    }

    /** A pod picked from the local catalog, for running without an ad server. */
    List<PodAd> localPod(Viewer viewer, RandomGenerator random) {
        var pod = new ArrayList<PodAd>(viewer.slots());
        for (int i = 0; i < viewer.slots(); i++) {
            pod.add(PodAd.of(catalog.pick(random)));
        }
        return pod;
    }

    Session plan(long startMs, RandomGenerator random) {
        Viewer viewer = viewer(random);
        return play(viewer, localPod(viewer, random), startMs, random);
    }

    Session planBot(long startMs, String botDeviceId, RandomGenerator random) {
        Viewer viewer = botViewer(botDeviceId, random);
        return play(viewer, localPod(viewer, random), startMs, random);
    }

    /** Turns a pod into the tracking events this viewer will fire, with their due times. */
    Session play(Viewer viewer, List<PodAd> pod, long startMs, RandomGenerator random) {
        return viewer.bot() ? playBot(viewer, pod, startMs) : playHuman(viewer, pod, startMs, random);
    }

    private Session playHuman(Viewer viewer, List<PodAd> pod, long startMs, RandomGenerator random) {
        var ads = new ArrayList<PodAd>();
        var types = new ArrayList<EventType>();
        var due = new ArrayList<Long>();

        long adStart = startMs;
        for (PodAd ad : pod) {
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
            if (reached < QUARTILES.length) {
                break;
            }
            adStart += durationMs;
        }
        return new Session(viewer.sessionId(), viewer.deviceId(), viewer.deviceType(), viewer.country(),
                viewer.consent(), false, ads.toArray(PodAd[]::new), types.toArray(EventType[]::new),
                due.stream().mapToLong(Long::longValue).toArray());
    }

    /**
     * Each ad: an impression, then a "complete" half a second later (no real player finishes a
     * 15-30s ad that fast), one ad per second, always from the same device.
     */
    private static Session playBot(Viewer viewer, List<PodAd> pod, long startMs) {
        int n = pod.size();
        var ads = new PodAd[n * 2];
        var types = new EventType[n * 2];
        var due = new long[n * 2];
        for (int a = 0; a < n; a++) {
            ads[2 * a] = pod.get(a);
            ads[2 * a + 1] = pod.get(a);
            types[2 * a] = EventType.IMPRESSION;
            types[2 * a + 1] = EventType.COMPLETE;
            due[2 * a] = startMs + a * 1_000L;
            due[2 * a + 1] = startMs + a * 1_000L + 500;
        }
        return new Session(viewer.sessionId(), viewer.deviceId(), viewer.deviceType(), viewer.country(),
                viewer.consent(), true, ads, types, due);
    }

    /** Average events per session, measured by sampling, so the engine can hit a target event rate. */
    double estimateEventsPerSession(RandomGenerator random, int samples) {
        long total = 0;
        for (int i = 0; i < samples; i++) {
            total += plan(0, random).size();
        }
        return (double) total / samples;
    }

    private static void insertSorted(ArrayList<PodAd> ads, ArrayList<EventType> types,
                                     ArrayList<Long> due, PodAd ad, EventType type, long at) {
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
