package dev.primetech.ads.simulator;

import dev.primetech.ads.common.ClientEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
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

    private final SessionPlanner planner;
    private final FaultInjector faults;
    private final RandomGenerator random;
    private final GroundTruth truth;
    private final double sessionsPerMs;
    private final long lateDelayMs;

    private final PriorityQueue<Session> active = new PriorityQueue<>(Comparator.comparingLong(Session::nextDueMs));
    private final PriorityQueue<Delayed> delayed = new PriorityQueue<>(Comparator.comparingLong(Delayed::sendAtMs));
    private long lastTickMs = -1;
    private double sessionCarry;

    TrafficEngine(SessionPlanner planner, FaultInjector faults, RandomGenerator random, GroundTruth truth,
                  double targetEventsPerSecond, long lateDelayMs) {
        this.planner = planner;
        this.faults = faults;
        this.random = random;
        this.truth = truth;
        this.lateDelayMs = lateDelayMs;
        double eventsPerSession = planner.estimateEventsPerSession(random, 2_000);
        this.sessionsPerMs = targetEventsPerSecond / eventsPerSession / 1000.0;
    }

    List<ClientEvent> tick(long nowMs) {
        startSessions(nowMs);
        var out = new ArrayList<ClientEvent>();

        while (!active.isEmpty() && active.peek().nextDueMs() <= nowMs) {
            Session s = active.poll();
            ClientEvent e = s.emitNext(random);
            route(e, nowMs, out);
            if (s.hasNext()) {
                active.add(s);
            }
        }
        while (!delayed.isEmpty() && delayed.peek().sendAtMs() <= nowMs) {
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
            active.add(planner.plan(nowMs, random));
            truth.sessions.increment();
        }
    }

    private void route(ClientEvent e, long nowMs, List<ClientEvent> out) {
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
        if (e.priceMicros() != null) {
            truth.billedMicros.add(e.priceMicros());
        }
    }
}
