package dev.primetech.ads.simulator;

import java.util.List;
import java.util.function.Consumer;

/** Where a viewer's ad break comes from: the local catalog, or the ad server. */
interface PodSource {

    /**
     * Asks for the ads of one break. {@code onPod} receives them (possibly none) exactly once, on
     * any thread; it must not block.
     */
    void request(SessionPlanner.Viewer viewer, Consumer<List<SessionPlanner.PodAd>> onPod);
}
