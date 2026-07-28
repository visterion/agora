package de.visterion.agora.fetch.earnings;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EarningsCoverageTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-07-27");

    @Test void fullWindowCoversAnything() {
        assertThat(EarningsCoverage.FULL_WINDOW.covers(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-01"), TODAY)).isTrue();
    }

    @Test void futureOnlyDoesNotCoverAnAllPastWindow() {
        // This is the get_earnings_window default (now-30 .. now): entirely in the past.
        assertThat(EarningsCoverage.FUTURE_ONLY.covers(
                LocalDate.parse("2026-06-27"), TODAY.minusDays(1), TODAY)).isFalse();
    }

    @Test void futureOnlyCoversAWindowEndingToday() {
        // "Future" includes today (US-Eastern), because post-market events of the current
        // trading day are the most decision-relevant rows there are.
        assertThat(EarningsCoverage.FUTURE_ONLY.covers(
                LocalDate.parse("2026-06-27"), TODAY, TODAY)).isTrue();
    }

    @Test void futureOnlyCoversAStraddlingWindow() {
        assertThat(EarningsCoverage.FUTURE_ONLY.covers(
                TODAY.minusDays(90), TODAY.plusDays(90), TODAY)).isTrue();
    }
}
