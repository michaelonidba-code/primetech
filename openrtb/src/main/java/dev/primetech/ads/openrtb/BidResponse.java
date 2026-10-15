package dev.primetech.ads.openrtb;

import java.util.List;

/**
 * OpenRTB 2.6 bid response. A bidder that does not want to bid answers HTTP 204 with no body,
 * or a response with an empty {@code seatbid} and a no-bid reason.
 *
 * @param nbr no-bid reason code
 */
public record BidResponse(String id, List<SeatBid> seatbid, String cur, Integer nbr) {

    public record SeatBid(String seat, List<Bid> bid) {
    }

    /**
     * @param impid   the {@link BidRequest.Imp#id()} this bid is for
     * @param price   bid CPM in the response currency
     * @param nurl    win notice URL; may contain {@code ${AUCTION_PRICE}} and {@code ${AUCTION_ID}}
     * @param adm     ad markup (a VAST document for video)
     * @param adomain advertiser domains, used for competitive separation and blocking
     * @param cid     campaign id on the bidder's side
     * @param crid    creative id on the bidder's side
     * @param dur     creative duration in seconds (2.6)
     */
    public record Bid(
            String id,
            String impid,
            double price,
            String nurl,
            String adm,
            List<String> adomain,
            String cid,
            String crid,
            Integer dur) {
    }
}
