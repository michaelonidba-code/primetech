package dev.primetech.ads.adserver;

import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.DeviceIdHasher;
import dev.primetech.ads.common.adserving.AdDecision;
import dev.primetech.ads.common.adserving.AdRequest;
import dev.primetech.ads.common.adserving.AdResponse;
import dev.primetech.ads.openrtb.BidRequest;
import dev.primetech.ads.openrtb.BidResponse;
import dev.primetech.ads.openrtb.OpenRtb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Answers one ad request:
 * <ol>
 *   <li>pseudonymise the device id (same keyed hash as the event pipeline, so caps and reporting agree);</li>
 *   <li>send one OpenRTB request to every DSP in parallel, with a hard deadline;</li>
 *   <li>meanwhile, filter direct-sold line items by targeting;</li>
 *   <li>drop candidates over their frequency cap (one Redis read for all of them);</li>
 *   <li>run the pod auction, record caps, notify winning DSPs, and log the decision.</li>
 * </ol>
 * A failing DSP, Redis or Kafka never fails the request: each degrades to fewer candidates,
 * no capping, or a missing log record, and is counted.
 */
public final class AdDecisionService {
    public record Settings(int maxSlots, Duration tmax, long floorCpmMicros, int auctionType, int dspFrequencyCapPerHour) {
    }

    private static final BidRequest.App APP = new BidRequest.App("primetech-ctv", "PrimeTech Streaming",
            "dev.primetech.tv", new BidRequest.Publisher("primetech", "PrimeTech"));

    private final Settings settings;
    private final DirectDemand direct;
    private final List<DspClient> dsps;
    private final FrequencyCaps caps;
    private final DecisionSink sink;
    private final DeviceIdHasher hasher;
    private final AdServerMetrics metrics;
    private final Set<String> announcedCampaigns = ConcurrentHashMap.newKeySet();

    public AdDecisionService(Settings settings, DirectDemand direct, List<DspClient> dsps, FrequencyCaps caps,
                             DecisionSink sink, DeviceIdHasher hasher, AdServerMetrics metrics) {
        this.settings = settings;
        this.direct = direct;
        this.dsps = dsps;
        this.caps = caps;
        this.sink = sink;
        this.hasher = hasher;
        this.metrics = metrics;
    }

    /**
     * Announces campaigns so the stream processor can enrich their events. A campaign counts as
     * announced only once the write succeeds; a failed one is forgotten and retried on its next win.
     */
    public CompletableFuture<Void> announce(List<CampaignMetadata> campaigns) {
        var writes = new ArrayList<CompletableFuture<Void>>();
        for (CampaignMetadata c : campaigns) {
            if (announcedCampaigns.add(c.campaignId())) {
                writes.add(sink.campaign(c).whenComplete((ok, error) -> {
                    if (error != null) {
                        announcedCampaigns.remove(c.campaignId());
                    }
                }));
            }
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    /** Checks a request; returns a problem description or null if it is acceptable. */
    public String validate(AdRequest r) {
        if (r.sessionId() == null || r.sessionId().isBlank() || r.sessionId().length() > 64) {
            return "sessionId is required (at most 64 characters)";
        }
        if (r.deviceType() == null) {
            return "deviceType is required";
        }
        if (r.country() == null || !r.country().matches("[A-Z]{2}")) {
            return "country must be an ISO 3166-1 alpha-2 code";
        }
        if (r.consent() == null) {
            return "consent is required";
        }
        if (r.slots() != null && (r.slots() < 1 || r.slots() > settings.maxSlots())) {
            return "slots must be between 1 and " + settings.maxSlots();
        }
        if (r.maxAdSeconds() != null && r.maxAdSeconds() < 5) {
            return "maxAdSeconds must be at least 5";
        }
        return null;
    }

    public AdResponse decide(AdRequest r) {
        long start = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        int slots = r.slots() == null ? 3 : r.slots();
        int maxAdSeconds = r.maxAdSeconds() == null ? 30 : r.maxAdSeconds();
        int podSeconds = r.podSeconds() == null ? slots * maxAdSeconds : r.podSeconds();
        String deviceIdHash = r.consent() && r.deviceId() != null && !r.deviceId().isBlank()
                ? hasher.hash(r.deviceId()) : null;

        // 1. Programmatic demand, in parallel and bounded by tmax.
        byte[] bidRequest = DspClient.serialize(bidRequest(requestId, r, deviceIdHash, slots, maxAdSeconds, podSeconds));
        List<CompletableFuture<DspClient.Result>> calls = dsps.stream()
                .map(d -> d.bid(bidRequest, settings.tmax())).toList();

        // 2. Direct demand while the DSPs think.
        List<Candidate> directCandidates = direct.eligible(r.country(), r.deviceType(), ThreadLocalRandom.current());

        List<DspClient.Result> results = calls.stream().map(CompletableFuture::join).toList();
        Map<Integer, List<Candidate>> dspCandidates = new HashMap<>();
        for (DspClient.Result result : results) {
            metrics.dsp(result);
            for (BidResponse.Bid bid : result.bids()) {
                Candidate c = fromBid(result.dsp(), bid);
                if (c != null) {
                    dspCandidates.computeIfAbsent(Integer.parseInt(c.impId()), k -> new ArrayList<>()).add(c);
                }
            }
        }

        // 3. Frequency caps, one read for every campaign in play.
        Set<String> cappedCampaigns = new HashSet<>();
        int cappedCandidates = 0;
        if (deviceIdHash != null) {
            Set<String> campaignIds = new HashSet<>();
            directCandidates.forEach(c -> campaignIds.add(c.campaignId()));
            dspCandidates.values().forEach(list -> list.forEach(c -> campaignIds.add(c.campaignId())));
            Map<String, Integer> limits = new HashMap<>();
            directCandidates.forEach(c -> limits.merge(c.campaignId(), c.frequencyCapPerHour(), Math::min));
            dspCandidates.values().forEach(list -> list.forEach(c -> limits.merge(c.campaignId(), c.frequencyCapPerHour(), Math::min)));
            try {
                caps.counts(deviceIdHash, campaignIds).forEach((campaign, seen) -> {
                    if (seen >= limits.getOrDefault(campaign, Integer.MAX_VALUE)) {
                        cappedCampaigns.add(campaign);
                    }
                });
            } catch (Exception e) {
                // Fail open: serving an ad a little too often beats serving nothing.
                metrics.capStoreError();
            }
            for (Candidate c : directCandidates) {
                if (cappedCampaigns.contains(c.campaignId())) {
                    cappedCandidates++;
                }
            }
            for (List<Candidate> list : dspCandidates.values()) {
                for (Candidate c : list) {
                    if (cappedCampaigns.contains(c.campaignId())) {
                        cappedCandidates++;
                    }
                }
            }
        }
        List<Candidate> directOk = directCandidates.stream().filter(c -> !cappedCampaigns.contains(c.campaignId())).toList();

        // 4. The auction.
        List<Auction.Winner> winners = Auction.run(slots, maxAdSeconds, podSeconds, position -> {
            List<Candidate> all = new ArrayList<>(directOk);
            for (Candidate c : dspCandidates.getOrDefault(position, List.of())) {
                if (!cappedCampaigns.contains(c.campaignId())) {
                    all.add(c);
                }
            }
            return all;
        }, settings.auctionType(), settings.floorCpmMicros());

        // 5. Side effects of winning.
        var served = new ArrayList<AdResponse.ServedAd>();
        var slotLog = new ArrayList<AdDecision.Slot>();
        List<String> wonCampaigns = new ArrayList<>();
        for (Auction.Winner w : winners) {
            Candidate c = w.candidate();
            long priceMicros = w.clearingCpmMicros() / 1000;
            served.add(new AdResponse.ServedAd(served.size() + 1, requestId, c.adId(), c.creativeId(), c.campaignId(),
                    c.lineItemId(), c.advertiserId(), c.durationSeconds(), priceMicros, c.source()));
            slotLog.add(new AdDecision.Slot(w.position(), c.source(), c.campaignId(), c.lineItemId(), c.advertiserId(),
                    c.durationSeconds(), c.bidCpmMicros(), w.clearingCpmMicros(), w.eligibleCandidates()));
            wonCampaigns.add(c.campaignId());
            metrics.win(c.source(), priceMicros);
            if (!c.isDirect()) {
                // DSPs only tell us the campaign id and advertiser domain; name the campaign by its id.
                String dspCampaign = c.campaignId().substring(c.campaignId().indexOf(':') + 1);
                announce(List.of(new CampaignMetadata(c.campaignId(), dspCampaign + " via " + c.source(),
                        c.advertiserId(), c.advertiserId())));
                if (c.nurl() != null) {
                    dsps.stream().filter(d -> d.name().equals(c.source())).findFirst()
                            .ifPresent(d -> d.notify(OpenRtb.expandMacros(c.nurl(), requestId, w.clearingCpmMicros())));
                }
            }
        }
        if (deviceIdHash != null && !wonCampaigns.isEmpty()) {
            try {
                caps.record(deviceIdHash, wonCampaigns);
            } catch (Exception e) {
                metrics.capStoreError();
            }
        }

        long latencyNanos = System.nanoTime() - start;
        metrics.request(slots, served.size(), latencyNanos, cappedCandidates);
        sink.decision(new AdDecision(requestId, System.currentTimeMillis(), r.sessionId(), r.deviceType(), r.country(),
                r.consent(), slots, served.size(), settings.auctionType(), settings.floorCpmMicros(),
                latencyNanos / 1_000_000, directCandidates.size(), cappedCandidates, slotLog,
                results.stream().map(x -> new AdDecision.Dsp(x.dsp(), x.status().name(), x.bids().size(), x.latencyMs())).toList()));
        return new AdResponse(requestId, served);
    }

    private BidRequest bidRequest(String requestId, AdRequest r, String deviceIdHash, int slots, int maxAdSeconds,
                                  int podSeconds) {
        var imps = new ArrayList<BidRequest.Imp>();
        for (int i = 1; i <= slots; i++) {
            int slotInPod = i == 1 ? 1 : (i == slots ? -1 : 0);
            imps.add(new BidRequest.Imp(String.valueOf(i),
                    new BidRequest.Video(List.of("video/mp4"), 5, maxAdSeconds, OpenRtb.VAST_PROTOCOLS, 1920, 1080,
                            OpenRtb.PLACEMENT_INSTREAM, requestId, slotInPod, 0, podSeconds),
                    OpenRtb.microsToCpm(settings.floorCpmMicros()), "USD", 1));
        }
        var device = new BidRequest.Device(OpenRtb.deviceType(r.deviceType()), new BidRequest.Geo(OpenRtb.alpha3(r.country())),
                deviceIdHash, deviceIdHash == null ? 1 : 0);
        return new BidRequest(requestId, imps, APP, device, settings.auctionType(), (int) settings.tmax().toMillis(),
                List.of("USD"), new BidRequest.Source(requestId));
    }

    /** Maps a bid onto our ids; bids we cannot use (bad imp id, no advertiser, no duration) are ignored. */
    private Candidate fromBid(String dsp, BidResponse.Bid bid) {
        if (bid.impid() == null || !bid.impid().matches("\\d+") || bid.adomain() == null || bid.adomain().isEmpty()
                || bid.cid() == null || bid.crid() == null || bid.dur() == null || bid.price() <= 0) {
            return null;
        }
        String campaignId = dsp + ":" + bid.cid();
        String creativeId = dsp + ":" + bid.crid();
        return new Candidate(dsp, bid.impid(), campaignId, campaignId, creativeId, creativeId, bid.adomain().get(0),
                bid.dur(), OpenRtb.cpmToMicros(bid.price()), settings.dspFrequencyCapPerHour(), bid.nurl());
    }
}
