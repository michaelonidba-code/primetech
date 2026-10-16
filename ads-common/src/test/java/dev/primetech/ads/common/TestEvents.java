package dev.primetech.ads.common;

public final class TestEvents {
    public static final long NOW = 1_760_000_000_000L;

    private TestEvents() {
    }

    public static ClientEvent impression() {
        return new ClientEvent("evt-1", EventType.IMPRESSION, NOW, "sess-1", "ad-1", "cr-1",
                "camp-1", "li-1", "device-abc", DeviceType.CTV, "US", true, 25_000L);
    }

    public static ClientEvent midpoint() {
        return new ClientEvent("evt-2", EventType.MIDPOINT, NOW, "sess-1", "ad-1", "cr-1",
                "camp-1", "li-1", "device-abc", DeviceType.CTV, "US", true, null);
    }
}
