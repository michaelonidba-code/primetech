package dev.primetech.ads.openrtb;

import dev.primetech.ads.common.DeviceType;

import java.util.Locale;
import java.util.MissingResourceException;

/** Conversions between our model and OpenRTB conventions. */
public final class OpenRtb {
    public static final String AUCTION_PRICE_MACRO = "${AUCTION_PRICE}";
    public static final String AUCTION_ID_MACRO = "${AUCTION_ID}";
    /** VAST 4.x wrapper and inline, the protocols a CTV player supports. */
    public static final java.util.List<Integer> VAST_PROTOCOLS = java.util.List.of(7, 8);
    public static final int PLACEMENT_INSTREAM = 1;

    private OpenRtb() {
    }

    /** OpenRTB device type list (AdCOM). */
    public static int deviceType(DeviceType type) {
        return switch (type) {
            case CTV -> 3;          // Connected TV
            case MOBILE -> 4;       // Phone
            case WEB -> 2;          // Personal computer
            case GAME_CONSOLE -> 6; // Connected device
        };
    }

    /** OpenRTB geo uses ISO-3166-1 alpha-3; our events use alpha-2. */
    public static String alpha3(String alpha2) {
        try {
            return Locale.of("", alpha2).getISO3Country();
        } catch (MissingResourceException e) {
            throw new IllegalArgumentException("unknown country " + alpha2, e);
        }
    }

    /** CPM in currency units (OpenRTB {@code price}) to micro-units, as we store money. */
    public static long cpmToMicros(double cpm) {
        return Math.round(cpm * 1_000_000);
    }

    public static double microsToCpm(long cpmMicros) {
        return cpmMicros / 1_000_000.0;
    }

    /** Substitutes the standard macros in a win or billing notice URL. */
    public static String expandMacros(String url, String auctionId, long clearingCpmMicros) {
        return url.replace(AUCTION_PRICE_MACRO, String.format(Locale.ROOT, "%.6f", microsToCpm(clearingCpmMicros)))
                .replace(AUCTION_ID_MACRO, auctionId);
    }
}
