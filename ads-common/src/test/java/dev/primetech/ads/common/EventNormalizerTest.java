package dev.primetech.ads.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventNormalizerTest {
    private static final String SECRET = "test-secret-0123456789";
    private final EventNormalizer normalizer = new EventNormalizer(new DeviceIdHasher(SECRET));

    @Test
    void hashesDeviceIdWhenConsented() {
        AdEvent out = normalizer.normalize(TestEvents.impression(), 42L);

        assertThat(out.deviceIdHash()).hasSize(64).isNotEqualTo("device-abc");
        assertThat(out.receivedTimeMs()).isEqualTo(42L);
        assertThat(out.schemaVersion()).isEqualTo(AdEvent.SCHEMA_VERSION);
        assertThat(out.priceMicros()).isEqualTo(25_000L);
    }

    @Test
    void hashIsStableForSameSecretAndDiffersAcrossSecrets() {
        var other = new DeviceIdHasher("another-secret-0123456789");
        var same = new DeviceIdHasher(SECRET);
        assertThat(same.hash("device-abc")).isEqualTo(new DeviceIdHasher(SECRET).hash("device-abc"));
        assertThat(other.hash("device-abc")).isNotEqualTo(same.hash("device-abc"));
    }

    @Test
    void dropsDeviceIdWithoutConsent() {
        var e = TestEvents.impression();
        var noConsent = new ClientEvent(e.eventId(), e.type(), e.eventTimeMs(), e.sessionId(), e.adId(),
                e.creativeId(), e.campaignId(), e.lineItemId(), e.deviceId(), e.deviceType(), e.country(), false,
                e.priceMicros());

        AdEvent out = normalizer.normalize(noConsent, 42L);

        assertThat(out.deviceIdHash()).isNull();
        assertThat(out.consent()).isFalse();
    }

    @Test
    void serializedEventNeverContainsRawDeviceId() throws Exception {
        String json = Json.MAPPER.writeValueAsString(normalizer.normalize(TestEvents.impression(), 42L));
        assertThat(json).doesNotContain("device-abc");
    }

    @Test
    void rejectsWeakSecret() {
        assertThatThrownBy(() -> new DeviceIdHasher("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
