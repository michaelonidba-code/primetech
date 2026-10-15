package dev.primetech.ads.adserver;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServedLedgerTest {
    private static final long P = 42;
    private static final String PREFIX = "pacing:served:42:";
    private static final String KEY = PREFIX + "li-1";

    private final ServedLedger ledger = new ServedLedger();

    @Test
    void seesItsOwnSpendingImmediately() {
        ledger.add(KEY, 100);
        ledger.add(KEY, 50);
        assertThat(ledger.served(P, "li-1", KEY)).isEqualTo(150);
    }

    @Test
    void failedWritesLoseNothing() {
        // Found live: per-request increments that failed while the stack warmed up were simply
        // dropped, and the counters ended 3-6% low. Totals that fail to write stay unwritten,
        // and the next write carries all of them.
        ledger.add(KEY, 100);
        Map<String, Long> attempt1 = ledger.unwritten(); // ... and Redis times out: not marked written
        ledger.add(KEY, 30);
        Map<String, Long> attempt2 = ledger.unwritten();

        assertThat(attempt1).containsEntry(KEY, 100L);
        assertThat(attempt2).containsEntry(KEY, 130L);
        ledger.written(attempt2);
        assertThat(ledger.unwritten()).isEmpty();
        assertThat(ledger.unwrittenMicros()).isZero();
    }

    @Test
    void combinesOtherInstancesWithItsOwnLatestTotal() {
        ledger.add(KEY, 100);
        ledger.written(ledger.unwritten()); // Redis now holds 100 for this instance
        // Read back: this instance's 100 plus another instance's 400.
        ledger.snapshot(P, Map.of("li-1", 500L), PREFIX);
        ledger.add(KEY, 20); // served after the read

        assertThat(ledger.served(P, "li-1", KEY)).isEqualTo(520);
    }

    @Test
    void aWriteThatFailedBeforeTheReadIsNotCountedTwiceOrMissed() {
        ledger.add(KEY, 100);
        ledger.written(ledger.unwritten()); // 100 written
        ledger.add(KEY, 50); // 150 total, but this write fails: Redis still holds 100 for us
        ledger.snapshot(P, Map.of("li-1", 100L + 400L), PREFIX);

        assertThat(ledger.served(P, "li-1", KEY)).isEqualTo(550);
    }

    @Test
    void anotherPeriodStartsFromThisInstancesOwnSpend() {
        ledger.snapshot(P - 1, Map.of("li-1", 999L), "pacing:served:41:");
        ledger.add(KEY, 10);
        assertThat(ledger.served(P, "li-1", KEY)).isEqualTo(10);
    }

    @Test
    void forgetsFinishedPeriodsOnlyOnceWritten() {
        String old = "pacing:served:41:li-1";
        ledger.add(old, 70);
        ledger.prune(PREFIX);
        assertThat(ledger.unwritten()).containsEntry(old, 70L); // not written yet: kept

        ledger.written(ledger.unwritten());
        ledger.prune(PREFIX);
        assertThat(ledger.unwritten()).isEmpty();
        assertThat(ledger.unwrittenMicros()).isZero();
    }
}
