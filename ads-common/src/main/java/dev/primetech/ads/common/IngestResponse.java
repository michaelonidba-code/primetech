package dev.primetech.ads.common;

import java.util.List;

/**
 * Per-batch result. Rejections are permanent (the client must not retry them); a non-2xx status
 * means nothing in the batch is guaranteed durable and the whole batch should be retried.
 */
public record IngestResponse(int accepted, int rejected, List<Rejection> errors) {

    public record Rejection(int index, String eventId, RejectReason reason) {
    }
}
