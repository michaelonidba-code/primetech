package dev.primetech.ads.simulator;

import dev.primetech.ads.common.ClientEvent;
import dev.primetech.ads.common.EventType;
import dev.primetech.ads.common.EventValidator;
import dev.primetech.ads.common.catalog.AdCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class SessionPlannerTest {
    private static final long START = 1_760_000_000_000L;

    private static List<ClientEvent> drain(Session s, SplittableRandom random) {
        var out = new ArrayList<ClientEvent>();
        while (s.hasNext()) {
            out.add(s.emitNext(random));
        }
        return out;
    }

    @Test
    void sessionsAreWellFormedAndValid() {
        var random = new SplittableRandom(7);
        var planner = new SessionPlanner(AdCatalog.generate(20, random));
        var validator = EventValidator.withDefaults();

        for (int i = 0; i < 500; i++) {
            List<ClientEvent> events = drain(planner.plan(START, random), random);

            assertThat(events).isNotEmpty();
            assertThat(events.get(0).type()).isEqualTo(EventType.IMPRESSION);
            assertThat(events).extracting(ClientEvent::eventTimeMs).isSorted();
            assertThat(events).extracting(ClientEvent::sessionId).containsOnly(events.get(0).sessionId());
            assertThat(events).extracting(ClientEvent::eventId).doesNotHaveDuplicates();
            assertThat(events).allSatisfy(e -> {
                assertThat(validator.validate(e, e.eventTimeMs())).as("event %s", e).isEmpty();
                assertThat(e.priceMicros() != null).isEqualTo(e.type() == EventType.IMPRESSION);
            });
        }
    }

    @Test
    void quartilesForOneAdNeverSkipAStep() {
        var random = new SplittableRandom(11);
        var planner = new SessionPlanner(AdCatalog.generate(5, random));

        for (int i = 0; i < 500; i++) {
            EventType last = null;
            for (ClientEvent e : drain(planner.plan(START, random), random)) {
                if (e.type() == EventType.CLICK) {
                    continue;
                }
                if (e.type() == EventType.IMPRESSION) {
                    assertThat(last).isIn(null, EventType.COMPLETE);
                } else {
                    assertThat(last).isNotNull();
                    assertThat(e.type().ordinal()).isEqualTo(last.ordinal() + 1);
                }
                last = e.type();
            }
        }
    }

    @Test
    void isDeterministicForASeed() {
        List<ClientEvent> a = run(99);
        List<ClientEvent> b = run(99);
        assertThat(a).isEqualTo(b);
    }

    private static List<ClientEvent> run(long seed) {
        var random = new SplittableRandom(seed);
        var planner = new SessionPlanner(AdCatalog.generate(10, random));
        return drain(planner.plan(START, random), random);
    }

    @Test
    void catalogTrafficIsSkewedTowardsTopCampaigns() {
        var random = new SplittableRandom(3);
        var catalog = AdCatalog.generate(50, random);
        int top = 0, bottom = 0;
        for (int i = 0; i < 20_000; i++) {
            String campaign = catalog.pick(random).campaignId();
            if (campaign.equals("camp-0000")) {
                top++;
            } else if (campaign.equals("camp-0049")) {
                bottom++;
            }
        }
        assertThat(top).isGreaterThan(bottom * 5);
    }
}
