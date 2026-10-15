package dev.primetech.ads.stream;

import dev.primetech.ads.common.AdEvent;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;

/**
 * Drops repeats of an event id. Keyed by event id, so the state is one small flag per event seen
 * within the TTL; with checkpointed state and a transactional sink, each event reaches the output
 * exactly once even across failures.
 *
 * <p>The TTL bounds memory: a duplicate arriving later than the TTL gets through. Client retries
 * happen within seconds, so 30 minutes leaves a wide margin.
 */
public final class Deduplicate extends KeyedProcessFunction<String, AdEvent, AdEvent> {
    private final Duration ttl;
    private transient ValueState<Boolean> seen;
    private transient Counter duplicates;

    public Deduplicate(Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public void open(OpenContext openContext) {
        var descriptor = new ValueStateDescriptor<>("seen", Boolean.class);
        descriptor.enableTimeToLive(StateTtlConfig.newBuilder(ttl)
                .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                .build());
        seen = getRuntimeContext().getState(descriptor);
        duplicates = getRuntimeContext().getMetricGroup().counter("duplicatesDropped");
    }

    @Override
    public void processElement(AdEvent event, Context ctx, Collector<AdEvent> out) throws Exception {
        if (seen.value() != null) {
            duplicates.inc();
            return;
        }
        seen.update(true);
        out.collect(event);
    }
}
