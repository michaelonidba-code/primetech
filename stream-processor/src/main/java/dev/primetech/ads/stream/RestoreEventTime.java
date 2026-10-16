package dev.primetech.ads.stream;

import dev.primetech.ads.common.EnrichedAdEvent;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;

/**
 * Sets each record's Flink timestamp back to its event time. Records re-emitted from a broadcast
 * input or a processing-time timer carry the wrong timestamp (or none), and event-time windows
 * assign by that timestamp. Watermarks pass through unchanged.
 */
public final class RestoreEventTime extends AbstractStreamOperator<EnrichedAdEvent>
        implements OneInputStreamOperator<EnrichedAdEvent, EnrichedAdEvent> {

    @Override
    public void processElement(StreamRecord<EnrichedAdEvent> element) {
        output.collect(element.replace(element.getValue(), element.getValue().eventTimeMs()));
    }
}
