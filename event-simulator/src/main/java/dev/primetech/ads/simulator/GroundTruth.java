package dev.primetech.ads.simulator;

import dev.primetech.ads.common.EventType;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the simulator actually produced. After de-duplication, the pipeline's counts should equal
 * {@link #uniqueValid}; the reconciliation job in a later phase checks exactly that.
 */
final class GroundTruth {
    final Map<EventType, LongAdder> uniqueValid = new EnumMap<>(EventType.class);
    final LongAdder billedMicros = new LongAdder();
    final LongAdder sessions = new LongAdder();
    final LongAdder duplicates = new LongAdder();
    final LongAdder late = new LongAdder();
    final LongAdder invalid = new LongAdder();

    GroundTruth() {
        for (EventType t : EventType.values()) {
            uniqueValid.put(t, new LongAdder());
        }
    }

    long uniqueValidTotal() {
        return uniqueValid.values().stream().mapToLong(LongAdder::sum).sum();
    }

    String summary() {
        var sb = new StringBuilder();
        sb.append("sessions=").append(sessions.sum())
                .append(" uniqueValid=").append(uniqueValidTotal())
                .append(" duplicates=").append(duplicates.sum())
                .append(" late=").append(late.sum())
                .append(" invalid=").append(invalid.sum())
                .append(" billedMicros=").append(billedMicros.sum())
                .append(" byType={");
        uniqueValid.forEach((t, n) -> sb.append(t).append('=').append(n.sum()).append(' '));
        return sb.append('}').toString();
    }
}
