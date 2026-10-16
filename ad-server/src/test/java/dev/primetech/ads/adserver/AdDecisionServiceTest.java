package dev.primetech.ads.adserver;

import com.sun.net.httpserver.HttpServer;
import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.DeviceIdHasher;
import dev.primetech.ads.common.DeviceType;
import dev.primetech.ads.common.adserving.AdDecision;
import dev.primetech.ads.common.adserving.AdRequest;
import dev.primetech.ads.common.adserving.AdResponse;
import dev.primetech.ads.common.catalog.AdCatalog;
import dev.primetech.ads.dsp.Bidder;
import dev.primetech.ads.dsp.DspProfile;
import dev.primetech.ads.dsp.DspServer;
import dev.primetech.ads.openrtb.BidRequest;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Real OpenRTB round trips against in-process DSPs: a good one, a slow one and a broken one. */
class AdDecisionServiceTest {
    private final List<AutoCloseable> servers = new ArrayList<>();
    private final RecordingSink sink = new RecordingSink();
    private AdServerMetrics metrics;

    static final class RecordingSink implements DecisionSink {
        final List<AdDecision> decisions = new CopyOnWriteArrayList<>();
        final List<CampaignMetadata> campaigns = new CopyOnWriteArrayList<>();
        volatile int failNextCampaigns;

        @Override
        public void decision(AdDecision decision) {
            decisions.add(decision);
        }

        @Override
        public CompletableFuture<Void> lineItem(dev.primetech.ads.common.catalog.LineItemConfig lineItem) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> campaign(CampaignMetadata campaign) {
            if (failNextCampaigns > 0) {
                failNextCampaigns--;
                return CompletableFuture.failedFuture(new RuntimeException("broker down"));
            }
            campaigns.add(campaign);
            return CompletableFuture.completedFuture(null);
        }
    }

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable s : servers) {
            s.close();
        }
    }

    private URI goodDsp(double cpm) throws IOException {
        var profile = new DspProfile("good-dsp", 1.0, cpm, 0.05, 1, 0, 1.0, List.of("brand.example", "other.example"));
        var server = new DspServer(0, new Bidder(profile, "http://localhost"),
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT), 1, false);
        server.start();
        servers.add(server);
        return URI.create("http://localhost:" + server.port() + "/openrtb2/bid");
    }

    /** Answers 500 or sleeps past any deadline. */
    private URI badDsp(boolean slow) throws IOException {
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            if (slow) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        servers.add(() -> server.stop(0));
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/");
    }

    private AdDecisionService service(Map<String, URI> dsps, int auctionType, int campaigns) {
        var catalog = AdCatalog.generate(campaigns, new SplittableRandom(42));
        // Standard line items always compete; guaranteed ones (which would take every slot) only when
        // the catalog is too small to have standard ones (the first five campaigns are guaranteed).
        if (campaigns <= 5) {
            return service(dsps, auctionType, catalog, new RecordingPacing(id -> 1.0));
        }
        var guaranteed = catalog.lineItems().stream()
                .filter(l -> l.tier() == dev.primetech.ads.common.catalog.LineItemConfig.Tier.GUARANTEED)
                .map(dev.primetech.ads.common.catalog.LineItemConfig::lineItemId).collect(java.util.stream.Collectors.toSet());
        return service(dsps, auctionType, catalog, new RecordingPacing(id -> guaranteed.contains(id) ? 0.0 : 1.0));
    }

    /** Pacing with fixed throttles that records what was charged. */
    static final class RecordingPacing implements PacingView {
        final java.util.function.ToDoubleFunction<String> throttles;
        final Map<String, Long> charged = new java.util.concurrent.ConcurrentHashMap<>();

        RecordingPacing(java.util.function.ToDoubleFunction<String> throttles) {
            this.throttles = throttles;
        }

        @Override
        public double throttle(String lineItemId) {
            return throttles.applyAsDouble(lineItemId);
        }

        @Override
        public void served(String lineItemId, long priceMicros) {
            charged.merge(lineItemId, priceMicros, Long::sum);
        }
    }

    private AdDecisionService service(Map<String, URI> dsps, int auctionType, AdCatalog catalog, PacingView pacing) {
        var http = DspClient.sharedHttpClient();
        var clients = new ArrayList<DspClient>();
        dsps.forEach((name, uri) -> clients.add(new DspClient(name, uri, http)));
        metrics = new AdServerMetrics(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT), new ArrayList<>(dsps.keySet()));
        return new AdDecisionService(new AdDecisionService.Settings(5, Duration.ofMillis(150), 5_000_000, auctionType, 2),
                new DirectDemand(catalog, pacing), clients, new InMemoryFrequencyCaps(), sink,
                new DeviceIdHasher("test-secret-0123456789"), metrics);
    }

    private static AdRequest request(String device, int slots) {
        return new AdRequest("sess-1", device, true, DeviceType.CTV, "US", slots, 30, slots * 30);
    }

    @Test
    void fillsAPodFromDirectAndProgrammaticDemand() throws Exception {
        // DSP bids around $80 CPM, above every direct line item ($20-60), so it wins where it bids.
        var service = service(Map.of("good-dsp", goodDsp(80)), BidRequest.FIRST_PRICE, 20);

        AdResponse response = service.decide(request("device-1", 3));

        assertThat(response.ads()).hasSize(3);
        assertThat(response.ads()).extracting(AdResponse.ServedAd::position).containsExactly(1, 2, 3);
        assertThat(response.ads()).allSatisfy(ad -> assertThat(ad.auctionId()).isEqualTo(response.requestId()));
        assertThat(response.ads()).extracting(AdResponse.ServedAd::advertiserId).doesNotHaveDuplicates();
        assertThat(response.ads()).extracting(AdResponse.ServedAd::source).contains("good-dsp", Candidate.DIRECT);
        var dspAd = response.ads().stream().filter(a -> a.source().equals("good-dsp")).findFirst().orElseThrow();
        assertThat(dspAd.campaignId()).startsWith("good-dsp:");
        assertThat(dspAd.priceMicros()).as("per-impression price from an ~$80 CPM").isBetween(70_000L, 90_000L);

        AdDecision decision = sink.decisions.get(0);
        assertThat(decision.requestId()).isEqualTo(response.requestId());
        assertThat(decision.slotsFilled()).isEqualTo(3);
        assertThat(decision.dsps()).singleElement().satisfies(d -> assertThat(d.status()).isEqualTo("BID"));
        assertThat(sink.campaigns).filteredOn(c -> c.campaignId().equals(dspAd.campaignId())).singleElement()
                .satisfies(c -> assertThat(c.campaignName()).isEqualTo(dspAd.campaignId().substring("good-dsp:".length()) + " via good-dsp"));
    }

    @Test
    void aSlowOrBrokenDspCostsNothingButItsBids() throws Exception {
        var service = service(Map.of("slow", badDsp(true), "broken", badDsp(false)), BidRequest.FIRST_PRICE, 20);

        long start = System.nanoTime();
        AdResponse response = service.decide(request("device-2", 2));
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(ms).as("bounded by tmax, not by the slow DSP").isLessThan(600);
        assertThat(response.ads()).as("direct demand still fills").hasSize(2);
        assertThat(sink.decisions.get(0).dsps()).extracting(AdDecision.Dsp::status)
                .containsExactlyInAnyOrder("TIMEOUT", "ERROR");
        @SuppressWarnings("unchecked")
        var dsps = (Map<String, Map<String, Long>>) metrics.snapshot().get("dsps");
        assertThat(dsps.get("slow").get("timeouts")).isEqualTo(1);
        assertThat(dsps.get("broken").get("errors")).isEqualTo(1);
    }

    @Test
    void frequencyCapsStopACampaignForOneDeviceOnly() {
        // One campaign, capped at 2-4 per hour: the same device runs out, another device does not.
        var service = service(Map.of(), BidRequest.FIRST_PRICE, 1);
        var served = new ArrayList<String>();
        for (int i = 0; i < 6; i++) {
            service.decide(request("device-3", 1)).ads().forEach(a -> served.add(a.campaignId()));
        }
        assertThat(served).hasSizeBetween(2, 4);
        assertThat(sink.decisions.get(5).frequencyCapped()).isPositive();

        assertThat(service.decide(request("device-4", 1)).ads()).hasSize(1);
    }

    @Test
    void withoutConsentNothingIsCappedOrForwarded() throws Exception {
        var service = service(Map.of("good-dsp", goodDsp(80)), BidRequest.FIRST_PRICE, 1);
        var anon = new AdRequest("sess-anon", "device-5", false, DeviceType.CTV, "US", 1, 30, 30);
        var seen = new HashSet<Integer>();
        for (int i = 0; i < 6; i++) {
            seen.add(service.decide(anon).ads().size());
        }
        assertThat(seen).containsExactly(1);
        assertThat(sink.decisions).allSatisfy(d -> assertThat(d.frequencyCapped()).isZero());
    }

    @Test
    void retriesCampaignAnnouncementsThatFailed() {
        var service = service(Map.of(), BidRequest.FIRST_PRICE, 1);
        var campaign = new CampaignMetadata("c1", "Campaign", "adv", "Advertiser");

        sink.failNextCampaigns = 1;
        assertThat(service.announce(List.of(campaign))).isCompletedExceptionally();
        assertThat(sink.campaigns).isEmpty();

        assertThat(service.announce(List.of(campaign))).isCompleted();
        service.announce(List.of(campaign));
        assertThat(sink.campaigns).as("announced once it succeeds, then not again").hasSize(1);
    }

    @Test
    void guaranteedLineItemsTakeTheFirstSlotsAndAreChargedToTheirBudget() throws Exception {
        var catalog = AdCatalog.generate(20, new SplittableRandom(42));
        var pacing = new RecordingPacing(id -> 1.0);
        var service = service(Map.of("good-dsp", goodDsp(80)), BidRequest.FIRST_PRICE, catalog, pacing);

        AdResponse response = service.decide(request("device-g", 2));

        var guaranteedIds = catalog.lineItems().stream()
                .filter(l -> l.tier() == dev.primetech.ads.common.catalog.LineItemConfig.Tier.GUARANTEED)
                .map(dev.primetech.ads.common.catalog.LineItemConfig::lineItemId).toList();
        assertThat(response.ads()).as("sponsors beat $80 bids").allSatisfy(a -> assertThat(guaranteedIds).contains(a.lineItemId()));
        for (var ad : response.ads()) {
            assertThat(pacing.charged.get(ad.lineItemId())).isEqualTo(ad.priceMicros());
        }
    }

    @Test
    void aSpentLineItemNoLongerCompetes() {
        var catalog = AdCatalog.generate(1, new SplittableRandom(42));
        var service = service(Map.of(), BidRequest.FIRST_PRICE, catalog, new RecordingPacing(id -> 0.0));

        assertThat(service.decide(request("device-x", 2)).ads()).isEmpty();
    }

    @Test
    void validatesRequests() {
        var service = service(Map.of(), BidRequest.FIRST_PRICE, 5);
        assertThat(service.validate(request("d", 3))).isNull();
        assertThat(service.validate(new AdRequest(null, "d", true, DeviceType.CTV, "US", 3, 30, 90))).contains("sessionId");
        assertThat(service.validate(new AdRequest("s", "d", true, DeviceType.CTV, "usa", 3, 30, 90))).contains("country");
        assertThat(service.validate(new AdRequest("s", "d", true, DeviceType.CTV, "US", 9, 30, 90))).contains("slots");
        assertThat(service.validate(new AdRequest("s", "d", null, DeviceType.CTV, "US", 3, 30, 90))).contains("consent");
    }
}
