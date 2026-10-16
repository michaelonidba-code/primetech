package dev.primetech.ads.adserver;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Ad server counters, exposed to Prometheus and summarised on {@code /stats} for the dashboard. */
public final class AdServerMetrics {
    private final MeterRegistry registry;
    private final Counter requests;
    private final Counter slotsRequested;
    private final Counter slotsFilled;
    private final Counter frequencyCapped;
    private final Counter capStoreErrors;
    private final Counter spendMicros;
    private final Counter shed;
    private final Timer latency;
    private final Map<String, Counter> winsBySource = new ConcurrentHashMap<>();
    private final Map<String, DspMeters> dsps = new LinkedHashMap<>();

    private record DspMeters(Counter requests, Counter bids, Counter noBids, Counter timeouts, Counter errors,
                             Counter wins, Timer latency) {
    }

    public AdServerMetrics(MeterRegistry registry, List<String> dspNames) {
        this.registry = registry;
        requests = registry.counter("adserver.requests");
        slotsRequested = registry.counter("adserver.slots.requested");
        slotsFilled = registry.counter("adserver.slots.filled");
        frequencyCapped = registry.counter("adserver.frequency.capped");
        capStoreErrors = registry.counter("adserver.frequency.store.errors");
        spendMicros = registry.counter("adserver.spend.micros");
        shed = registry.counter("adserver.requests.shed");
        latency = Timer.builder("adserver.decision.latency")
                .description("Time to answer an ad request, including the DSP auction")
                .publishPercentiles(0.5, 0.99).publishPercentileHistogram().register(registry);
        for (String dsp : dspNames) {
            dsps.put(dsp, new DspMeters(
                    registry.counter("adserver.dsp.requests", "dsp", dsp),
                    registry.counter("adserver.dsp.bids", "dsp", dsp),
                    registry.counter("adserver.dsp.nobids", "dsp", dsp),
                    registry.counter("adserver.dsp.timeouts", "dsp", dsp),
                    registry.counter("adserver.dsp.errors", "dsp", dsp),
                    registry.counter("adserver.dsp.wins", "dsp", dsp),
                    Timer.builder("adserver.dsp.latency").tag("dsp", dsp)
                            .publishPercentiles(0.5, 0.99).register(registry)));
        }
    }

    void request(int slots, int filled, long latencyNanos, int capped) {
        requests.increment();
        slotsRequested.increment(slots);
        slotsFilled.increment(filled);
        frequencyCapped.increment(capped);
        latency.record(latencyNanos, TimeUnit.NANOSECONDS);
    }

    void shed() {
        shed.increment();
    }

    void capStoreError() {
        capStoreErrors.increment();
    }

    void win(String source, long priceMicros) {
        winsBySource.computeIfAbsent(source, s -> registry.counter("adserver.wins", "source", s)).increment();
        spendMicros.increment(priceMicros);
        DspMeters d = dsps.get(source);
        if (d != null) {
            d.wins.increment();
        }
    }

    void dsp(DspClient.Result r) {
        DspMeters d = dsps.get(r.dsp());
        if (d == null) {
            return;
        }
        d.requests.increment();
        d.latency.record(r.latencyMs(), TimeUnit.MILLISECONDS);
        switch (r.status()) {
            case BID -> d.bids.increment(r.bids().size());
            case NO_BID -> d.noBids.increment();
            case TIMEOUT -> d.timeouts.increment();
            case ERROR -> d.errors.increment();
        }
    }

    public Map<String, Object> snapshot() {
        var out = new LinkedHashMap<String, Object>();
        out.put("timestampMs", System.currentTimeMillis());
        out.put("requests", (long) requests.count());
        out.put("slotsRequested", (long) slotsRequested.count());
        out.put("slotsFilled", (long) slotsFilled.count());
        out.put("shed", (long) shed.count());
        out.put("frequencyCapped", (long) frequencyCapped.count());
        out.put("capStoreErrors", (long) capStoreErrors.count());
        out.put("spendMicros", (long) spendMicros.count());
        out.put("latencyP50Ms", percentile(latency, 0.5));
        out.put("latencyP99Ms", percentile(latency, 0.99));
        var wins = new LinkedHashMap<String, Long>();
        winsBySource.forEach((k, v) -> wins.put(k, (long) v.count()));
        out.put("winsBySource", wins);
        var dspStats = new LinkedHashMap<String, Object>();
        dsps.forEach((name, d) -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("requests", (long) d.requests.count());
            m.put("bids", (long) d.bids.count());
            m.put("noBids", (long) d.noBids.count());
            m.put("timeouts", (long) d.timeouts.count());
            m.put("errors", (long) d.errors.count());
            m.put("wins", (long) d.wins.count());
            m.put("latencyP50Ms", percentile(d.latency, 0.5));
            m.put("latencyP99Ms", percentile(d.latency, 0.99));
            dspStats.put(name, m);
        });
        out.put("dsps", dspStats);
        return out;
    }

    private static double percentile(Timer timer, double p) {
        for (ValueAtPercentile v : timer.takeSnapshot().percentileValues()) {
            if (v.percentile() == p) {
                return v.value(TimeUnit.MILLISECONDS);
            }
        }
        return 0;
    }
}
