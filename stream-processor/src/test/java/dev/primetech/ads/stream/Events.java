package dev.primetech.ads.stream;

import dev.primetech.ads.common.AdEvent;
import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.DeviceType;
import dev.primetech.ads.common.EnrichedAdEvent;
import dev.primetech.ads.common.EventType;

/** Test fixtures. Times are relative to a minute boundary so window assignment is obvious. */
final class Events {
    static final long T0 = 1_760_000_040_000L; // a whole minute
    static final CampaignMetadata CAMP_1 = new CampaignMetadata("camp-1", "Spring Launch", "adv-1", "Harbor Bank");
    static final CampaignMetadata CAMP_2 = new CampaignMetadata("camp-2", "Summer Sale", "adv-2", "Juniper Foods");

    private Events() {
    }

    static AdEvent event(String id, EventType type, long timeMs, String campaign, String device) {
        return new AdEvent(1, id, type, timeMs, timeMs + 50, "sess-" + id, "ad-" + campaign, "cr-" + campaign,
                campaign, campaign + "-li-0", device, DeviceType.CTV, "US", device != null,
                type == EventType.IMPRESSION ? 30_000L : null);
    }

    static EnrichedAdEvent enriched(String id, EventType type, long timeMs, String device) {
        return EnrichedAdEvent.of(event(id, type, timeMs, "camp-1", device), CAMP_1, timeMs);
    }
}
