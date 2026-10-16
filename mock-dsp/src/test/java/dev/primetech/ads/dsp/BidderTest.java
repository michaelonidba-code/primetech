package dev.primetech.ads.dsp;

import dev.primetech.ads.openrtb.BidRequest;
import dev.primetech.ads.openrtb.BidResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BidderTest {
    private static final DspProfile ALWAYS = new DspProfile("test-dsp", 1.0, 20.0, 0.3, 30, 0.3, 0.8,
            List.of("brand.example", "other.example"));

    static BidRequest request(int slots, double floor, int maxDuration, int auctionType) {
        var imps = IntStream.rangeClosed(1, slots).mapToObj(i -> new BidRequest.Imp(String.valueOf(i),
                new BidRequest.Video(List.of("video/mp4"), 5, maxDuration, List.of(7), 1920, 1080, 1, "pod", i == 1 ? 1 : 0, 0, null),
                floor, "USD", 1)).toList();
        return new BidRequest("auc", imps, null, new BidRequest.Device(3, new BidRequest.Geo("USA"), "h", 0),
                auctionType, 120, List.of("USD"), null);
    }

    private static List<BidResponse.Bid> bids(DspProfile profile, BidRequest request, int rounds) {
        var bidder = new Bidder(profile, "http://dsp");
        var random = new SplittableRandom(1);
        var out = new ArrayList<BidResponse.Bid>();
        for (int i = 0; i < rounds; i++) {
            BidResponse r = bidder.bid(request, random);
            if (r != null) {
                out.addAll(r.seatbid().get(0).bid());
            }
        }
        return out;
    }

    @Test
    void bidsOncePerImpressionWithWellFormedBids() {
        var bidder = new Bidder(ALWAYS, "http://dsp");
        BidResponse r = bidder.bid(request(3, 0, 30, 1), new SplittableRandom(2));

        assertThat(r.id()).isEqualTo("auc");
        assertThat(r.seatbid().get(0).bid()).extracting(BidResponse.Bid::impid).containsExactly("1", "2", "3");
        assertThat(r.seatbid().get(0).bid()).allSatisfy(b -> {
            assertThat(b.adomain()).hasSize(1);
            assertThat(b.nurl()).contains("${AUCTION_PRICE}").contains("${AUCTION_ID}");
            assertThat(b.adm()).startsWith("<VAST");
            assertThat(b.crid()).startsWith(b.cid());
        });
    }

    @Test
    void neverBidsBelowTheFloor() {
        var all = bids(ALWAYS, request(1, 25.0, 30, 1), 2_000);
        assertThat(all).isNotEmpty().allSatisfy(b -> assertThat(b.price()).isGreaterThanOrEqualTo(25.0));
    }

    @Test
    void shadesBidsInFirstPriceAuctionsOnly() {
        double firstPrice = bids(ALWAYS, request(1, 0, 30, BidRequest.FIRST_PRICE), 4_000).stream()
                .mapToDouble(BidResponse.Bid::price).average().orElseThrow();
        double secondPrice = bids(ALWAYS, request(1, 0, 30, BidRequest.SECOND_PRICE), 4_000).stream()
                .mapToDouble(BidResponse.Bid::price).average().orElseThrow();
        assertThat(firstPrice / secondPrice).isBetween(0.75, 0.85);
    }

    @Test
    void respectsMaxDurationAndBidRate() {
        var shortSlots = bids(ALWAYS, request(1, 0, 15, 1), 500);
        assertThat(shortSlots).allSatisfy(b -> assertThat(b.dur()).isEqualTo(15));
        assertThat(bids(ALWAYS, request(1, 0, 10, 1), 100)).as("nothing fits 10s").isEmpty();

        var half = new DspProfile("half", 0.5, 20, 0.3, 30, 0.3, 0.8, List.of("a.example"));
        assertThat(bids(half, request(1, 0, 30, 1), 4_000)).hasSizeBetween(1_800, 2_200);
    }

    @Test
    void latencyFollowsTheProfile() {
        var bidder = new Bidder(DspProfile.PRESETS.get("slow"), "http://dsp");
        var random = new SplittableRandom(3);
        long overDeadline = IntStream.range(0, 10_000).filter(i -> bidder.latencyMs(random) > 110).count();
        assertThat(overDeadline).as("the slow preset misses a 110 ms budget a meaningful share of the time")
                .isBetween(1_500L, 4_000L);
    }
}
