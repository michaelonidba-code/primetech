package dev.primetech.ads.simulator;

import dev.primetech.ads.common.ClientEvent;
import dev.primetech.ads.common.DeviceType;
import dev.primetech.ads.common.EventType;

import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * One viewer's ad break, planned up front but emitted lazily. Only the plan (a few arrays) is
 * held in memory, so hundreds of thousands of concurrent sessions stay cheap.
 */
final class Session {
    final String sessionId;
    final String deviceId;
    final DeviceType deviceType;
    final String country;
    final boolean consent;

    private final AdCatalog.AdSpec[] ads;
    private final EventType[] types;
    private final long[] dueMs;
    private int next;

    Session(String sessionId, String deviceId, DeviceType deviceType, String country, boolean consent,
            AdCatalog.AdSpec[] ads, EventType[] types, long[] dueMs) {
        this.sessionId = sessionId;
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.country = country;
        this.consent = consent;
        this.ads = ads;
        this.types = types;
        this.dueMs = dueMs;
    }

    boolean hasNext() {
        return next < types.length;
    }

    long nextDueMs() {
        return dueMs[next];
    }

    int size() {
        return types.length;
    }

    /** Emits the next planned event. {@code random} only supplies the event id. */
    ClientEvent emitNext(RandomGenerator random) {
        int i = next++;
        AdCatalog.AdSpec ad = ads[i];
        EventType type = types[i];
        return new ClientEvent(
                new UUID(random.nextLong(), random.nextLong()).toString(),
                type,
                dueMs[i],
                sessionId,
                ad.adId(),
                ad.creativeId(),
                ad.campaignId(),
                ad.lineItemId(),
                deviceId,
                deviceType,
                country,
                consent,
                type.isBillable() ? ad.impressionPriceMicros() : null);
    }
}
