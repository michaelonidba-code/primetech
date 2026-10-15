package dev.primetech.ads.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Pseudonymises device ids with HMAC-SHA256. A plain hash of a device id can be reversed by
 * hashing every known id; a keyed hash cannot without the secret, and rotating the secret
 * unlinks old and new data.
 */
public final class DeviceIdHasher {
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public DeviceIdHasher(String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("device id hashing secret must be at least 16 characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hash(String deviceId) {
        try {
            // Mac instances are not thread-safe and cheap to create relative to a network request.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(deviceId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }
}
