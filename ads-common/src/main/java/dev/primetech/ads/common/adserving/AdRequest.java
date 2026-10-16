package dev.primetech.ads.common.adserving;

import dev.primetech.ads.common.DeviceType;

/**
 * A player asking for the ads of one ad break (pod). Like tracking events, it may carry a raw
 * device id; the ad server pseudonymises it before using or forwarding it.
 *
 * @param slots              most ads the player will show in this break
 * @param maxAdSeconds       longest single ad that fits a slot
 * @param podSeconds         total length of the break
 */
public record AdRequest(
        String sessionId,
        String deviceId,
        Boolean consent,
        DeviceType deviceType,
        String country,
        Integer slots,
        Integer maxAdSeconds,
        Integer podSeconds) {
}
