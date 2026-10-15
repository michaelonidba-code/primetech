package dev.primetech.ads.gateway;

import dev.primetech.ads.common.AdEvent;
import dev.primetech.ads.common.DeadLetter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

final class FakeSink implements EventSink {
    final List<AdEvent> events = new CopyOnWriteArrayList<>();
    final List<DeadLetter> deadLetters = new CopyOnWriteArrayList<>();
    volatile boolean failing;

    @Override
    public CompletableFuture<Void> publish(AdEvent event) {
        if (failing) {
            return CompletableFuture.failedFuture(new RuntimeException("broker down"));
        }
        events.add(event);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> publishDeadLetter(DeadLetter deadLetter) {
        if (failing) {
            return CompletableFuture.failedFuture(new RuntimeException("broker down"));
        }
        deadLetters.add(deadLetter);
        return CompletableFuture.completedFuture(null);
    }
}
