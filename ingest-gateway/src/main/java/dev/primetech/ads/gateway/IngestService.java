package dev.primetech.ads.gateway;

import dev.primetech.ads.common.ClientEvent;
import dev.primetech.ads.common.DeadLetter;
import dev.primetech.ads.common.EventNormalizer;
import dev.primetech.ads.common.EventValidator;
import dev.primetech.ads.common.IngestResponse;
import dev.primetech.ads.common.RejectReason;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Validates, scrubs and publishes one batch. Delivery is at-least-once: the batch is acknowledged
 * only after Kafka confirms every event, and on any failure the client retries the whole batch.
 * Duplicates from those retries are removed downstream by event id.
 */
public final class IngestService {
    private final EventValidator validator;
    private final EventNormalizer normalizer;
    private final EventSink sink;
    private final IngestMetrics metrics;
    private final LongSupplier clock;
    private final Duration publishTimeout;

    public IngestService(EventValidator validator, EventNormalizer normalizer, EventSink sink,
                         IngestMetrics metrics, LongSupplier clock, Duration publishTimeout) {
        this.validator = validator;
        this.normalizer = normalizer;
        this.sink = sink;
        this.metrics = metrics;
        this.clock = clock;
        this.publishTimeout = publishTimeout;
    }

    /**
     * @throws PublishFailedException if any write did not complete; nothing in the batch should be
     *                                treated as delivered
     */
    public IngestResponse ingest(List<ClientEvent> events) throws PublishFailedException {
        long now = clock.getAsLong();
        var writes = new ArrayList<CompletableFuture<Void>>(events.size());
        var errors = new ArrayList<IngestResponse.Rejection>();
        var acceptedEvents = new ArrayList<ClientEvent>(events.size());

        for (int i = 0; i < events.size(); i++) {
            ClientEvent e = events.get(i);
            if (e == null) {
                errors.add(new IngestResponse.Rejection(i, null, RejectReason.MISSING_FIELD));
                continue;
            }
            Optional<RejectReason> problem = validator.validate(e, now);
            if (problem.isPresent()) {
                errors.add(new IngestResponse.Rejection(i, e.eventId(), problem.get()));
                writes.add(sink.publishDeadLetter(DeadLetter.of(problem.get(), now, e)));
            } else {
                acceptedEvents.add(e);
                writes.add(sink.publish(normalizer.normalize(e, now)));
            }
        }

        try {
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            metrics.publishFailed();
            throw new PublishFailedException(ex);
        } catch (Exception ex) {
            metrics.publishFailed();
            throw new PublishFailedException(ex);
        }

        // Count only after the writes are durable, so the counters match what is in Kafka.
        metrics.batch();
        acceptedEvents.forEach(e -> metrics.accepted(e.type()));
        errors.forEach(r -> metrics.rejected(r.reason()));
        return new IngestResponse(acceptedEvents.size(), errors.size(), errors);
    }

    public static final class PublishFailedException extends Exception {
        PublishFailedException(Throwable cause) {
            super(cause);
        }
    }
}
