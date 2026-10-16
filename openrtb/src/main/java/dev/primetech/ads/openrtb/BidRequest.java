package dev.primetech.ads.openrtb;

import java.util.List;

/**
 * OpenRTB 2.6 bid request, limited to the fields a CTV video ad pod needs. Field names follow the
 * specification exactly so the JSON is wire-compatible with real exchanges and DSPs.
 *
 * @param id     unique auction id
 * @param imp    one impression per slot in the pod
 * @param at     auction type: 1 first price, 2 second price plus
 * @param tmax   time the bidder has to respond, in milliseconds, including network latency
 * @param cur    allowed bid currencies (ISO-4217)
 * @param source transaction id shared by every party in the supply chain
 */
public record BidRequest(
        String id,
        List<Imp> imp,
        App app,
        Device device,
        Integer at,
        Integer tmax,
        List<String> cur,
        Source source) {

    public static final int FIRST_PRICE = 1;
    public static final int SECOND_PRICE = 2;

    /** @param bidfloor minimum CPM for this impression, in {@code bidfloorcur} */
    public record Imp(String id, Video video, Double bidfloor, String bidfloorcur, Integer secure) {
    }

    /**
     * Video placement. The pod fields are new in 2.6: every slot of one ad break shares a
     * {@code podid}, {@code slotinpod} says where in the break this one sits.
     *
     * @param plcmt     placement type: 1 = instream
     * @param slotinpod 1 first, -1 last, 2 first or last, 0 any
     * @param podseq    position of the pod in the content: 1 pre-roll, 0 mid-roll, -1 post-roll
     */
    public record Video(
            List<String> mimes,
            Integer minduration,
            Integer maxduration,
            List<Integer> protocols,
            Integer w,
            Integer h,
            Integer plcmt,
            String podid,
            Integer slotinpod,
            Integer podseq,
            Integer poddur) {
    }

    public record App(String id, String name, String bundle, Publisher publisher) {
    }

    public record Publisher(String id, String name) {
    }

    /**
     * @param devicetype OpenRTB device type list, see {@link OpenRtb#deviceType}
     * @param ifa        advertising id: here the pseudonymised device id, only with consent
     * @param lmt        limit ad tracking: 1 when the viewer did not consent
     */
    public record Device(Integer devicetype, Geo geo, String ifa, Integer lmt) {
    }

    /** @param country ISO-3166-1 alpha-3, as OpenRTB requires */
    public record Geo(String country) {
    }

    public record Source(String tid) {
    }
}
