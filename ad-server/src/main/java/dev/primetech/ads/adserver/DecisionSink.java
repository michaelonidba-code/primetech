package dev.primetech.ads.adserver;

import dev.primetech.ads.common.CampaignMetadata;
import dev.primetech.ads.common.adserving.AdDecision;
import dev.primetech.ads.common.catalog.LineItemConfig;

import java.util.concurrent.CompletableFuture;

/** Where the ad server records decisions and announces campaigns. Calls must not block. */
public interface DecisionSink extends AutoCloseable {

    /** Best effort: a lost audit record is counted, never retried by the caller. */
    void decision(AdDecision decision);

    /**
     * Must eventually succeed, or the stream processor cannot enrich the campaign's events; the
     * returned future completes when the broker has the record, so callers can retry.
     */
    CompletableFuture<Void> campaign(CampaignMetadata campaign);

    /** Line item terms for the pacing service; completes when the broker has the record. */
    CompletableFuture<Void> lineItem(LineItemConfig lineItem);

    @Override
    default void close() {
    }
}
