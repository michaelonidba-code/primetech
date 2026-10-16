package dev.primetech.ads.pacing;

import dev.primetech.ads.common.pacing.PacingState;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Where pacing reads served spend and publishes its decisions (Redis in production). */
public interface PacingStore {

    /** The ad server's served-spend counters for these line items in the given period. */
    Map<String, Long> served(long periodIndex, Collection<String> lineItemIds) throws Exception;

    void publish(List<PacingState> states) throws Exception;
}
