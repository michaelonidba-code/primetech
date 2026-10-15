package dev.primetech.ads.audit;

import com.fasterxml.jackson.databind.JsonNode;
import dev.primetech.ads.common.Json;
import dev.primetech.ads.common.LineItemMinute;
import dev.primetech.ads.common.catalog.LineItemConfig;
import dev.primetech.ads.common.pacing.PacingPeriod;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AuditServiceTest {
    private static final PacingPeriod PERIOD = PacingPeriod.of(Duration.ofMinutes(10));
    private static final long P = 3_000_000;
    private static final long START = PERIOD.start(P);

    /** Sources that answer from maps, or fail like a down Druid or Redis. */
    static final class FakeSources implements Sources {
        final Map<String, Checks.Totals> druidMinutes = new HashMap<>();
        final Map<String, Long> druidSpend = new HashMap<>();
        final Map<String, Long> redis = new HashMap<>();
        boolean druidDown;
        long minutesFrom, minutesTo, spendFrom, spendTo;

        @Override
        public Map<String, Checks.Totals> druidMinutes(long from, long to) throws IOException {
            if (druidDown) throw new IOException("connection refused");
            minutesFrom = from;
            minutesTo = to;
            return druidMinutes;
        }

        @Override
        public Map<String, Long> druidSpend(long from, long to) throws IOException {
            if (druidDown) throw new IOException("connection refused");
            spendFrom = from;
            spendTo = to;
            return druidSpend;
        }

        @Override
        public Map<String, Long> redisServed(long periodIndex, Collection<String> ids) {
            return redis;
        }
    }

    private final AuditLedger ledger = new AuditLedger(PERIOD);
    private final FakeSources sources = new FakeSources();
    // Five minutes after period P ended: P is settled.
    private final AtomicLong now = new AtomicLong(PERIOD.end(P) + 5 * 60_000);
    private final AtomicBoolean caughtUp = new AtomicBoolean(true);
    private final List<CheckResult> published = new ArrayList<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AuditService service = new AuditService(ledger, sources, PERIOD, now::get, caughtUp::get,
            published::add, registry);

    private static JsonNode decision(long ts, String lineItemId, long clearingCpmMicros) throws IOException {
        return Json.MAPPER.readTree("""
                {"timestampMs": %d, "slots": [
                  {"source": "direct", "lineItemId": "%s", "clearingCpmMicros": %d},
                  {"source": "premium-dsp", "lineItemId": null, "clearingCpmMicros": 9000000}]}"""
                .formatted(ts, lineItemId, clearingCpmMicros));
    }

    private CheckResult result(List<CheckResult> results, String check) {
        return results.stream().filter(r -> r.check().equals(check)).findFirst().orElseThrow();
    }

    private void healthyPeriod() throws IOException {
        ledger.lineItem(new LineItemConfig("li-1", "c", "a", LineItemConfig.Tier.STANDARD, 30_000_000, 10_000_000));
        ledger.decision(decision(START + 1_000, "li-1", 30_000_000)); // $0.03
        ledger.decision(decision(START + 2_000, "li-1", 30_000_000));
        ledger.decision(decision(PERIOD.end(P) + 60_000, "li-1", 30_000_000)); // next period: log read past the end
        sources.redis.put("li-1", 60_000L);
        sources.druidSpend.put("li-1", 58_000L);
    }

    @Test
    void reportsPendingUntilTheTopicsHaveBeenRead() {
        caughtUp.set(false);
        assertThat(service.run()).allMatch(r -> r.status() == CheckResult.Status.PENDING);
        assertThat(published).isEmpty();
        assertThat(registry.get("audit.check.ok").tag("check", Checks.BUDGETS).gauge().value()).isNaN();
    }

    @Test
    void checksTheLastSettledPeriodAndPublishesTheResults() throws IOException {
        healthyPeriod();
        List<CheckResult> results = service.run();

        assertThat(results).allMatch(r -> r.status() == CheckResult.Status.OK);
        assertThat(sources.spendFrom).isEqualTo(START);
        assertThat(sources.spendTo).isEqualTo(PERIOD.end(P));
        assertThat(published).hasSize(4);
        assertThat(registry.get("audit.check.ok").tag("check", Checks.SERVED_VS_DECISIONS).gauge().value()).isEqualTo(1);
    }

    @Test
    void comparesOnlyMinutesThatCanNoLongerChange() throws IOException {
        healthyPeriod();
        service.run();
        // The newest minute compared ended at least 12 minutes ago (10 min allowed lateness + margin).
        assertThat(now.get() - sources.minutesTo).isGreaterThanOrEqualTo(12 * 60_000);
        assertThat(sources.minutesTo - sources.minutesFrom).isEqualTo(30 * 60_000);
    }

    @Test
    void aBrokenCounterIsAMismatchAndShowsInTheMetrics() throws IOException {
        healthyPeriod();
        sources.redis.put("li-1", 30_000L); // lost one increment
        CheckResult r = result(service.run(), Checks.SERVED_VS_DECISIONS);

        assertThat(r.status()).isEqualTo(CheckResult.Status.MISMATCH);
        assertThat(registry.get("audit.check.ok").tag("check", Checks.SERVED_VS_DECISIONS).gauge().value()).isZero();
        assertThat(registry.get("audit.check.mismatches").tag("check", Checks.SERVED_VS_DECISIONS).gauge().value()).isEqualTo(1);
    }

    @Test
    void aDownSourceIsAnErrorThatKeepsTheLastVerdict() throws IOException {
        healthyPeriod();
        service.run();
        sources.druidDown = true;
        List<CheckResult> results = service.run();

        assertThat(result(results, Checks.FLINK_VS_DRUID).status()).isEqualTo(CheckResult.Status.ERROR);
        assertThat(result(results, Checks.BUDGETS).status()).isEqualTo(CheckResult.Status.ERROR);
        // Redis still answers, so that check still runs.
        assertThat(result(results, Checks.SERVED_VS_DECISIONS).status()).isEqualTo(CheckResult.Status.OK);
        // An error says nothing about the data: the gauge keeps the last real verdict, and the
        // last-completed timestamp stops advancing, which is what the staleness alert watches.
        assertThat(registry.get("audit.check.ok").tag("check", Checks.BUDGETS).gauge().value()).isEqualTo(1);
    }

    @Test
    void waitsForTheDecisionLogToPassThePeriodEnd() throws IOException {
        ledger.lineItem(new LineItemConfig("li-1", "c", "a", LineItemConfig.Tier.STANDARD, 30_000_000, 10_000_000));
        ledger.decision(decision(START + 1_000, "li-1", 30_000_000)); // nothing read after P yet
        assertThat(result(service.run(), Checks.SERVED_VS_DECISIONS).status()).isEqualTo(CheckResult.Status.PENDING);
    }

    @Test
    void keepsTheLatestRevisionOfEachMinute() {
        ledger.minute(minute(2, 7));
        ledger.minute(minute(1, 5)); // an older revision arriving late
        assertThat(ledger.minutes(0, Long.MAX_VALUE).values()).containsExactly(new Checks.Totals(7, 7_000));
    }

    @Test
    void resetForgetsDecisionsSoAReReadDoesNotDoubleCount() throws IOException {
        ledger.decision(decision(START + 1_000, "li-1", 30_000_000));
        ledger.reset();
        ledger.decision(decision(START + 1_000, "li-1", 30_000_000));
        assertThat(ledger.served(P)).containsEntry("li-1", 30_000L);
    }

    private static LineItemMinute minute(int revision, long impressions) {
        return new LineItemMinute("li-1", "c", "a", START, START + 60_000, revision, impressions, 0, 0, 0, 0, 0,
                impressions * 1_000, 0, START);
    }
}
