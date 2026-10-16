package dev.primetech.ads.simulator;

import dev.primetech.ads.common.ClientEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.random.RandomGenerator;

/**
 * Drives the simulation by time. Each {@link #tick(long)} starts new sessions at the rate needed to
 * reach the target event rate and returns every event that has come due, in event-time order
 * (apart from injected faults). It does no I/O and takes time as an argument, so tests can run
 * minutes of traffic in milliseconds.
 */
final class TrafficEngine {
    private record Delayed(long sendAtMs, ClientEvent event) {
    }

    private record Ready(SessionPlanner.Viewer viewer, List<SessionPlanner.PodAd> pod) {
    }

    /** Bot traffic settings: share of new sessions run by bots, and how many bot devices exist. */
    record Bots(double sessionShare, int devices) {
        static final Bots NONE = new Bots(0, 1);
    }

    private final SessionPlanner planner;
    private final FaultInjector faults;
    private final RandomGenerator random;
    private final GroundTruth truth;
    private final double sessionsPerMs;
    private final long lateDelayMs;
    private final Bots bots;
    private final String[] botDeviceIds;
    private final PodSource pods;
    // Pods arrive from ad server callbacks on other threads; the engine itself stays single-threaded.
    private final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();

    private final PriorityQueue<Session> active = new PriorityQueue<>(Comparator.comparingLong(Session::nextDueMs));
    private final PriorityQueue<Delayed> delayed = new PriorityQueue<>(Comparator.comparingLong(Delayed::sendAtMs));
    private long lastTickMs = -1;
    private double sessionCarry;

    /** Engine that picks ads from the local catalog. */
    TrafficEngine(SessionPlanner planner, FaultInjector faults, RandomGenerator random, GroundTruth truth,
                  double targetEventsPerSecond, long lateDelayMs, Bots bots) {
        this(planner, faults, random, truth, targetEventsPerSecond, lateDelayMs, bots, null);
    }

    /** @param pods where breaks get their ads; null means the local catalog */
    TrafficEngine(SessionPlanner planner, FaultInjector faults, RandomGenerator random, GroundTruth truth,
                  double targetEventsPerSecond, long lateDelayMs, Bots bots, PodSource pods) {
        this.planner = planner;
        this.pods = pods != null ? pods : (viewer, onPod) -> onPod.accept(planner.localPod(viewer, random));
        this.bots = bots;
        this.botDeviceIds = new String[bots.devices()];
        for (int i = 0; i < botDeviceIds.length; i++) {
            botDeviceIds[i] = "bot-" + Long.toHexString(random.nextLong());
        }
        this.faults = faults;
        this.random = random;
        this.truth = truth;
        this.lateDelayMs = lateDelayMs;
        double eventsPerSession = planner.estimateEventsPerSession(random, 2_000);
        this.sessionsPerMs = targetEventsPerSecond / eventsPerSession / 1000.0;
    }

    List<ClientEvent> tick(long nowMs) {
        startSessions(nowMs);
        Ready r;
        while ((r = ready.poll()) != null) {
            if (r.pod().isEmpty()) {
                truth.noFill.increment();
            } else {
                active.add(planner.play(r.viewer(), r.pod(), nowMs, random));
            }
        }
        var out = new ArrayList<ClientEvent>();

        while (!active.isEmpty() && active.peek().nextDueMs() <= nowMs) {
            Session s = active.poll();
            ClientEvent e = s.emitNext(random);
            route(e, s.bot, nowMs, out);
            if (s.hasNext()) {
                active.add(s);
            }
        }
        while (!delayed.isEmpty() && delayed.peek().sendAtMs() <= nowMs) {
            out.add(delayed.poll().event());
        }
        return out;
    }

    /**
     * Ends the run: late and duplicate copies still waiting are released now, so every event counted
     * in the ground truth is actually sent. Sessions still in progress simply stop, which is fine
     * because truth only counts events already emitted.
     */
    List<ClientEvent> drainDelayed() {
        var out = new ArrayList<ClientEvent>(delayed.size());
        while (!delayed.isEmpty()) {
            out.add(delayed.poll().event());
        }
        return out;
    }

    int activeSessions() {
        return active.size();
    }

    private void startSessions(long nowMs) {
        if (lastTickMs < 0) {
            lastTickMs = nowMs;
            return;
        }
        sessionCarry += (nowMs - lastTickMs) * sessionsPerMs;
        lastTickMs = nowMs;
        int toStart = (int) sessionCarry;
        sessionCarry -= toStart;
        for (int i = 0; i < toStart; i++) {
            SessionPlanner.Viewer viewer;
            if (bots.sessionShare() > 0 && random.nextDouble() < bots.sessionShare()) {
                viewer = planner.botViewer(botDeviceIds[random.nextInt(botDeviceIds.length)], random);
                truth.botSessions.increment();
            } else {
                viewer = planner.viewer(random);
            }
            truth.sessions.increment();
            pods.request(viewer, pod -> ready.add(new Ready(viewer, pod)));
        }
    }

    private void route(ClientEvent e, boolean bot, long nowMs, List<ClientEvent> out) {
        switch (faults.choose(random)) {
            case INVALID -> {
                truth.invalid.increment();
                out.add(FaultInjector.corrupt(e, nowMs, random));
                return;
            }
            case LATE -> {
                truth.late.increment();
                delayed.add(new Delayed(nowMs + lateDelayMs, e));
            }
            case DUPLICATE -> {
                // A retry after a lost response: same event id, sent again a few seconds later.
                truth.duplicates.increment();
                out.add(e);
                delayed.add(new Delayed(nowMs + 1_000 + random.nextInt(9_000), e));
            }
            case NONE -> out.add(e);
        }
        truth.uniqueValid.get(e.type()).increment();
        if (bot) {
            truth.botEvents.increment();
        }
        if (e.priceMicros() != null) {
            truth.billedMicros.add(e.priceMicros());
        }
    }
}
