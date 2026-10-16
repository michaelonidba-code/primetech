package dev.primetech.ads.adserver;

import dev.primetech.ads.openrtb.BidRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuctionTest {
    private static final long FLOOR = 5_000_000;

    static Candidate direct(String advertiser, long cpm, int seconds) {
        return new Candidate(Candidate.DIRECT, null, "camp-" + advertiser, "li-" + advertiser, "ad-" + advertiser,
                "cr-" + advertiser + "-" + seconds, advertiser, seconds, cpm, 3, null);
    }

    static Candidate dsp(String dsp, int slot, String advertiser, long cpm, int seconds) {
        return new Candidate(dsp, String.valueOf(slot), dsp + ":" + advertiser, dsp + ":" + advertiser,
                dsp + ":cr-" + advertiser, dsp + ":cr-" + advertiser + slot, advertiser, seconds, cpm, 4, "http://win");
    }

    private static List<Auction.Winner> run(int slots, int podSeconds, Map<Integer, List<Candidate>> bySlot, int type) {
        return Auction.run(slots, 30, podSeconds, p -> bySlot.getOrDefault(p, List.of()), type, FLOOR);
    }

    @Test
    void highestOfferWinsAndPaysItsBidInFirstPrice() {
        var winners = run(1, 30, Map.of(1, List.of(
                direct("a", 20_000_000, 30), dsp("dsp", 1, "b", 31_500_000, 15), dsp("dsp2", 1, "c", 25_000_000, 15))),
                BidRequest.FIRST_PRICE);

        assertThat(winners).singleElement().satisfies(w -> {
            assertThat(w.candidate().advertiserId()).isEqualTo("b");
            assertThat(w.clearingCpmMicros()).isEqualTo(31_500_000);
            assertThat(w.eligibleCandidates()).isEqualTo(3);
        });
    }

    @Test
    void secondPricePaysRunnerUpPlusOneCentButNeverMoreThanTheBid() {
        var winners = run(1, 30, Map.of(1, List.of(dsp("dsp", 1, "b", 31_500_000, 15), dsp("dsp2", 1, "c", 25_000_000, 15))),
                BidRequest.SECOND_PRICE);
        assertThat(winners.get(0).clearingCpmMicros()).isEqualTo(25_010_000);

        var tied = run(1, 30, Map.of(1, List.of(dsp("dsp", 1, "b", 25_000_000, 15), dsp("dsp2", 1, "c", 25_000_000, 15))),
                BidRequest.SECOND_PRICE);
        assertThat(tied.get(0).clearingCpmMicros()).as("capped at the winner's bid").isEqualTo(25_000_000);

        var alone = run(1, 30, Map.of(1, List.of(dsp("dsp", 1, "b", 31_500_000, 15))), BidRequest.SECOND_PRICE);
        assertThat(alone.get(0).clearingCpmMicros()).as("sole bidder pays the floor").isEqualTo(FLOOR);
    }

    @Test
    void directLineItemsPayTheirContractedCpm() {
        var winners = run(1, 30, Map.of(1, List.of(direct("a", 40_000_000, 30), dsp("dsp", 1, "b", 10_000_000, 15))),
                BidRequest.SECOND_PRICE);
        assertThat(winners.get(0).candidate().isDirect()).isTrue();
        assertThat(winners.get(0).clearingCpmMicros()).isEqualTo(40_000_000);
    }

    @Test
    void ignoresBidsUnderTheFloor() {
        assertThat(run(1, 30, Map.of(1, List.of(dsp("dsp", 1, "b", 4_990_000, 15))), BidRequest.FIRST_PRICE)).isEmpty();
    }

    @Test
    void anAdvertiserAppearsAtMostOncePerBreak() {
        var a = direct("a", 50_000_000, 15);
        var winners = run(3, 90, Map.of(
                1, List.of(a, dsp("dsp", 1, "b", 30_000_000, 15)),
                2, List.of(a, dsp("dsp", 2, "a", 45_000_000, 15), dsp("dsp", 2, "c", 20_000_000, 15)),
                3, List.of(a)), BidRequest.FIRST_PRICE);

        assertThat(winners).extracting(w -> w.candidate().advertiserId()).containsExactly("a", "c");
        assertThat(winners).extracting(Auction.Winner::position).containsExactly(1, 2);
    }

    @Test
    void adsMustFitTheRemainingBreak() {
        var winners = run(3, 45, Map.of(
                1, List.of(direct("a", 50_000_000, 30)),
                2, List.of(direct("b", 40_000_000, 30), dsp("dsp", 2, "c", 10_000_000, 15)),
                3, List.of(direct("d", 40_000_000, 15))), BidRequest.FIRST_PRICE);

        assertThat(winners).extracting(w -> w.candidate().advertiserId()).containsExactly("a", "c");
        assertThat(winners.stream().mapToInt(w -> w.candidate().durationSeconds()).sum()).isLessThanOrEqualTo(45);
    }

    @Test
    void tiesGoToDirectDemand() {
        var winners = run(1, 30, Map.of(1, List.of(dsp("dsp", 1, "b", 30_000_000, 15), direct("a", 30_000_000, 15))),
                BidRequest.FIRST_PRICE);
        assertThat(winners.get(0).candidate().isDirect()).isTrue();
    }
}
