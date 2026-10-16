package dev.primetech.ads.adserver;

import dev.primetech.ads.openrtb.BidRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Fills an ad pod slot by slot. Each slot is a unified auction between direct-sold line items and
 * the DSP bids for that slot, under the rules a CTV break needs:
 *
 * <ul>
 *   <li>bids below the floor are ignored;</li>
 *   <li>competitive separation: an advertiser (and so a campaign) appears at most once per break;</li>
 *   <li>the ads must fit the break's total length.</li>
 * </ul>
 *
 * Guaranteed line items (sponsorships) take a slot ahead of the auction whenever pacing lets them
 * compete; the auction only prices what is left. Direct line items pay their contracted CPM. DSP winners pay their bid in a first-price auction,
 * or the next highest eligible bid plus one cent (never less than the floor, never more than their
 * bid) in a second-price auction.
 */
public final class Auction {
    static final long ONE_CENT_CPM_MICROS = 10_000;

    /**
     * Guaranteed line items first (priority tier, regardless of price), then highest offer; ties go
     * to direct demand, then a stable order so results are repeatable.
     */
    private static final Comparator<Candidate> RANKING = Comparator
            .comparing((Candidate c) -> !c.guaranteed())
            .thenComparing(Comparator.comparingLong(Candidate::bidCpmMicros).reversed())
            .thenComparing(c -> !c.isDirect())
            .thenComparing(Candidate::creativeId);

    public record Winner(int position, Candidate candidate, long clearingCpmMicros, int eligibleCandidates) {
    }

    private Auction() {
    }

    /**
     * @param candidatesForSlot every candidate for a 1-based slot position (direct and that slot's bids)
     */
    public static List<Winner> run(int slots, int maxAdSeconds, int podSeconds, IntFunction<List<Candidate>> candidatesForSlot,
                                   int auctionType, long floorCpmMicros) {
        var winners = new ArrayList<Winner>();
        Set<String> advertisersInPod = new HashSet<>();
        int secondsLeft = podSeconds;

        for (int position = 1; position <= slots; position++) {
            int fits = Math.min(maxAdSeconds, secondsLeft);
            List<Candidate> eligible = new ArrayList<>();
            for (Candidate c : candidatesForSlot.apply(position)) {
                if (c.bidCpmMicros() >= floorCpmMicros
                        && c.durationSeconds() <= fits
                        && !advertisersInPod.contains(c.advertiserId())) {
                    eligible.add(c);
                }
            }
            if (eligible.isEmpty()) {
                continue;
            }
            eligible.sort(RANKING);
            Candidate winner = eligible.get(0);
            winners.add(new Winner(position, winner, clearingPrice(winner, eligible, auctionType, floorCpmMicros),
                    eligible.size()));
            advertisersInPod.add(winner.advertiserId());
            secondsLeft -= winner.durationSeconds();
        }
        return winners;
    }

    private static long clearingPrice(Candidate winner, List<Candidate> ranked, int auctionType, long floor) {
        if (winner.isDirect() || auctionType == BidRequest.FIRST_PRICE) {
            return winner.bidCpmMicros();
        }
        // Runner-up plus a cent; a sole bidder pays the floor.
        long price = ranked.size() > 1 ? Math.max(floor, ranked.get(1).bidCpmMicros() + ONE_CENT_CPM_MICROS) : floor;
        return Math.min(winner.bidCpmMicros(), price);
    }
}
