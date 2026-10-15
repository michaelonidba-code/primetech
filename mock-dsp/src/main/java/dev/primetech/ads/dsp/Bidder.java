package dev.primetech.ads.dsp;

import dev.primetech.ads.openrtb.BidRequest;
import dev.primetech.ads.openrtb.BidResponse;
import dev.primetech.ads.openrtb.OpenRtb;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * The bidding logic of a mock DSP, separate from HTTP so it can be tested directly. For each
 * impression it decides whether to bid, what the impression is worth, and what to bid given the
 * auction type and floor.
 */
public final class Bidder {
    private static final int[] DURATIONS = {15, 30};

    private final DspProfile profile;
    private final String winUrlBase;

    public Bidder(DspProfile profile, String winUrlBase) {
        this.profile = profile;
        this.winUrlBase = winUrlBase;
    }

    /** Returns null for no bid (answered with HTTP 204). */
    public BidResponse bid(BidRequest request, RandomGenerator random) {
        boolean firstPrice = request.at() == null || request.at() == BidRequest.FIRST_PRICE;
        var bids = new ArrayList<BidResponse.Bid>();
        for (BidRequest.Imp imp : request.imp()) {
            if (random.nextDouble() >= profile.bidRate()) {
                continue;
            }
            int maxDuration = imp.video() != null && imp.video().maxduration() != null ? imp.video().maxduration() : 30;
            int duration = pickDuration(maxDuration, random);
            if (duration < 0) {
                continue;
            }
            double value = logNormal(profile.cpmMedian(), profile.cpmSigma(), random);
            // First price: shade below value. Second price: bidding true value is the dominant strategy.
            double price = firstPrice ? value * profile.firstPriceShade() : value;
            double floor = imp.bidfloor() == null ? 0 : imp.bidfloor();
            if (price < floor) {
                if (value < floor) {
                    continue;
                }
                price = floor;
            }
            price = Math.round(price * 100) / 100.0;

            String advertiser = profile.advertisers().get(random.nextInt(profile.advertisers().size()));
            String campaign = campaignId(advertiser, random.nextInt(2));
            String creative = campaign + "-" + duration + "s";
            bids.add(new BidResponse.Bid(
                    UUID.randomUUID().toString(), imp.id(), price,
                    winUrlBase + "/win?auction=" + OpenRtb.AUCTION_ID_MACRO + "&imp=" + imp.id()
                            + "&price=" + OpenRtb.AUCTION_PRICE_MACRO,
                    vast(creative, duration), List.of(advertiser), campaign, creative, duration));
        }
        if (bids.isEmpty()) {
            return null;
        }
        return new BidResponse(request.id(), List.of(new BidResponse.SeatBid(profile.name(), bids)), "USD", null);
    }

    /** How long this response takes, drawn per request. */
    public long latencyMs(RandomGenerator random) {
        return Math.round(logNormal(profile.latencyMedianMs(), profile.latencySigma(), random));
    }

    static String campaignId(String advertiserDomain, int n) {
        return advertiserDomain.substring(0, advertiserDomain.indexOf('.')) + "-c" + n;
    }

    private static int pickDuration(int maxDuration, RandomGenerator random) {
        int fitting = 0;
        for (int d : DURATIONS) {
            if (d <= maxDuration) {
                fitting++;
            }
        }
        if (fitting == 0) {
            return -1;
        }
        return DURATIONS[random.nextInt(fitting)];
    }

    private static double logNormal(double median, double sigma, RandomGenerator random) {
        return median * Math.exp(sigma * random.nextGaussian());
    }

    private static String vast(String creativeId, int duration) {
        return "<VAST version=\"4.2\"><Ad id=\"" + creativeId + "\"><InLine><Creatives><Creative><Linear>"
                + "<Duration>00:00:" + String.format("%02d", duration) + "</Duration>"
                + "</Linear></Creative></Creatives></InLine></Ad></VAST>";
    }
}
