package dev.primetech.ads.adserver;

import dev.primetech.ads.common.DeviceType;
import dev.primetech.ads.common.catalog.AdCatalog;
import dev.primetech.ads.common.catalog.LineItemConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogFileTest {

    private static final String SPONSOR = """
            {"generatedAt": "2026-10-21T10:00:00Z",
             "campaigns": [{"campaignId": "sp-launch", "name": "Launch promo", "advertiserId": "spon-1world1dollar",
               "advertiserName": "The One Dollar World",
               "lineItems": [{"lineItemId": "sp-launch-li", "tier": "GUARANTEED", "cpmMicros": 20000000,
                 "periodBudgetMicros": 500000, "countries": ["ng", "US"], "deviceTypes": [], "frequencyCapPerHour": 3,
                 "creatives": [{"adId": "ad-cr-launch", "creativeId": "cr-launch", "durationSeconds": 30}]}]}]}
            """;

    @Test
    void readsSponsorCampaigns(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("catalog.json");
        Files.writeString(file, SPONSOR);
        AdCatalog catalog = CatalogFile.read(file);

        assertThat(catalog.campaigns()).singleElement()
                .satisfies(c -> assertThat(c.advertiserName()).isEqualTo("The One Dollar World"));
        assertThat(catalog.lineItems()).singleElement().satisfies(l -> {
            assertThat(l.tier()).isEqualTo(LineItemConfig.Tier.GUARANTEED);
            assertThat(l.periodBudgetMicros()).isEqualTo(500_000);
        });
        AdCatalog.AdSpec ad = catalog.ads().getFirst();
        assertThat(ad.creativeId()).isEqualTo("cr-launch");
        assertThat(ad.impressionPriceMicros()).isEqualTo(20_000);
        assertThat(ad.targets("NG", DeviceType.WEB)).isTrue();
        assertThat(ad.targets("GB", DeviceType.WEB)).isFalse();
        assertThat(catalog.pick(new SplittableRandom(1))).isEqualTo(ad);
    }

    @Test
    void missingFileIsAnEmptyCatalog(@TempDir Path dir) throws Exception {
        AdCatalog catalog = CatalogFile.read(dir.resolve("absent.json"));
        assertThat(catalog.ads()).isEmpty();
        assertThat(catalog.campaigns()).isEmpty();
    }

    @Test
    void malformedFilesAreRejectedNotEmptied(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("catalog.json");
        Files.writeString(file, SPONSOR.replace("\"durationSeconds\": 30", "\"durationSeconds\": 0"));
        assertThatThrownBy(() -> CatalogFile.read(file)).hasMessageContaining("durationSeconds");
        Files.writeString(file, SPONSOR.replace("\"cpmMicros\": 20000000,", ""));
        assertThatThrownBy(() -> CatalogFile.read(file)).hasMessageContaining("cpmMicros");
        Files.writeString(file, "{not json");
        assertThatThrownBy(() -> CatalogFile.read(file)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void directDemandSwapsCatalogs(@TempDir Path dir) throws Exception {
        var direct = new DirectDemand(AdCatalog.of(java.util.List.of(), java.util.List.of(), java.util.List.of()),
                PacingView.fixed(1.0));
        assertThat(direct.eligible("NG", DeviceType.WEB, new SplittableRandom(1))).isEmpty();
        Path file = dir.resolve("catalog.json");
        Files.writeString(file, SPONSOR);
        direct.update(CatalogFile.read(file));
        assertThat(direct.eligible("NG", DeviceType.WEB, new SplittableRandom(1)))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.creativeId()).isEqualTo("cr-launch");
                    assertThat(c.guaranteed()).isTrue();
                });
        assertThat(direct.eligible("GB", DeviceType.WEB, new SplittableRandom(1))).isEmpty();
    }
}
