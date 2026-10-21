package dev.primetech.ads.adserver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.DeviceType;
import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.catalog.AdCatalog;
import dev.primetech.ads.common.catalog.LineItemConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Real direct-sold campaigns (sponsors) from a JSON file written by the dashboard, instead of the
 * generated practice catalog. A missing file is an empty catalog: no direct demand, nothing served.
 *
 * <pre>{@code
 * {"campaigns": [{"campaignId": "sp-1", "name": "...", "advertiserId": "spon-acme", "advertiserName": "Acme",
 *   "lineItems": [{"lineItemId": "sp-1-li", "tier": "GUARANTEED", "cpmMicros": 20000000,
 *     "periodBudgetMicros": 1000000, "countries": ["NG"], "deviceTypes": [], "frequencyCapPerHour": 4,
 *     "creatives": [{"adId": "ad-cr-1", "creativeId": "cr-1", "durationSeconds": 30}]}]}]}
 * }</pre>
 */
public final class CatalogFile {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FileCatalog(List<FileCampaign> campaigns) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FileCampaign(String campaignId, String name, String advertiserId, String advertiserName,
                        List<FileLineItem> lineItems) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FileLineItem(String lineItemId, String tier, Long cpmMicros, Long periodBudgetMicros, List<String> countries,
                        List<String> deviceTypes, Integer frequencyCapPerHour, List<FileCreative> creatives) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FileCreative(String adId, String creativeId, Integer durationSeconds) {
    }

    /** Reads and validates the file. Throws on a malformed file so a bad write never empties a live catalog. */
    public static AdCatalog read(Path path) throws IOException {
        FileCatalog file;
        try {
            file = Json.MAPPER.readValue(Files.readAllBytes(path), FileCatalog.class);
        } catch (NoSuchFileException e) {
            return AdCatalog.of(List.of(), List.of(), List.of());
        }
        return toCatalog(file);
    }

    static AdCatalog toCatalog(FileCatalog file) {
        var ads = new ArrayList<AdCatalog.AdSpec>();
        var campaigns = new ArrayList<CampaignMetadata>();
        var lineItems = new ArrayList<LineItemConfig>();
        var seenLineItems = new HashSet<String>();
        for (FileCampaign c : file.campaigns() == null ? List.<FileCampaign>of() : file.campaigns()) {
            require(c.campaignId(), "campaignId");
            require(c.advertiserId(), "advertiserId");
            campaigns.add(new CampaignMetadata(c.campaignId(), orElse(c.name(), c.campaignId()), c.advertiserId(),
                    orElse(c.advertiserName(), c.advertiserId())));
            for (FileLineItem l : c.lineItems() == null ? List.<FileLineItem>of() : c.lineItems()) {
                require(l.lineItemId(), "lineItemId");
                if (!seenLineItems.add(l.lineItemId())) {
                    throw new IllegalArgumentException("duplicate lineItemId " + l.lineItemId());
                }
                long cpm = positive(l.cpmMicros(), "cpmMicros");
                long budget = positive(l.periodBudgetMicros(), "periodBudgetMicros");
                var tier = "STANDARD".equalsIgnoreCase(l.tier()) ? LineItemConfig.Tier.STANDARD : LineItemConfig.Tier.GUARANTEED;
                lineItems.add(new LineItemConfig(l.lineItemId(), c.campaignId(), c.advertiserId(), tier, cpm, budget));
                Set<String> countries = new HashSet<>();
                for (String country : l.countries() == null ? List.<String>of() : l.countries()) {
                    countries.add(country.toUpperCase(Locale.ROOT));
                }
                var devices = EnumSet.noneOf(DeviceType.class);
                for (String d : l.deviceTypes() == null ? List.<String>of() : l.deviceTypes()) {
                    devices.add(DeviceType.valueOf(d.toUpperCase(Locale.ROOT)));
                }
                int cap = l.frequencyCapPerHour() == null || l.frequencyCapPerHour() <= 0 ? 4 : l.frequencyCapPerHour();
                for (FileCreative cr : l.creatives() == null ? List.<FileCreative>of() : l.creatives()) {
                    require(cr.creativeId(), "creativeId");
                    int duration = cr.durationSeconds() == null ? 0 : cr.durationSeconds();
                    if (duration <= 0 || duration > 300) {
                        throw new IllegalArgumentException("creative " + cr.creativeId() + ": durationSeconds must be 1-300");
                    }
                    ads.add(new AdCatalog.AdSpec(orElse(cr.adId(), "ad-" + cr.creativeId()), cr.creativeId(), c.campaignId(),
                            l.lineItemId(), c.advertiserId(), cpm, duration, Set.copyOf(countries), devices, cap));
                }
            }
        }
        return AdCatalog.of(ads, campaigns, lineItems);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static long positive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private CatalogFile() {
    }
}
