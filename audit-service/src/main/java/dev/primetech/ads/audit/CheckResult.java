package dev.primetech.ads.audit;

import java.util.List;

/**
 * The outcome of one run of one reconciliation.
 *
 * @param compared   how many items (line item minutes, line items) were compared
 * @param mismatches how many disagreed
 * @param summary    one line for people: what was compared and what was found
 * @param examples   up to a few disagreeing items, for whoever investigates
 */
public record CheckResult(
        String check,
        Status status,
        long windowStartMs,
        long windowEndMs,
        long compared,
        long mismatches,
        String summary,
        List<String> examples,
        long ranAtMs) {

    public enum Status {
        /** Every compared item agreed. */
        OK,
        /** At least one item disagreed: the sources tell different stories. */
        MISMATCH,
        /** Not enough settled data yet (e.g. just started); not a failure. */
        PENDING,
        /** A source could not be read; the check says nothing about the data. */
        ERROR
    }

    static final int MAX_EXAMPLES = 5;

    static CheckResult of(String check, long from, long to, long compared, List<String> problems, String summary, long now) {
        return new CheckResult(check, problems.isEmpty() ? Status.OK : Status.MISMATCH, from, to, compared,
                problems.size(), summary, List.copyOf(problems.subList(0, Math.min(MAX_EXAMPLES, problems.size()))), now);
    }

    static CheckResult pending(String check, String why, long now) {
        return new CheckResult(check, Status.PENDING, 0, 0, 0, 0, why, List.of(), now);
    }

    static CheckResult error(String check, String why, long now) {
        return new CheckResult(check, Status.ERROR, 0, 0, 0, 0, why, List.of(), now);
    }
}
