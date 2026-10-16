package dev.primetech.ads.gateway;

import dev.primetech.ads.common.AdEvent;
import dev.primetech.ads.common.DeadLetter;

import java.util.concurrent.CompletableFuture;

/** Where the gateway puts events. Completes once the write is durable (Kafka acks=all). */
public interface EventSink extends AutoCloseable {

    CompletableFuture<Void> publish(AdEvent event);

    CompletableFuture<Void> publishDeadLetter(DeadLetter deadLetter);

    @Override
    default void close() {
    }
}
