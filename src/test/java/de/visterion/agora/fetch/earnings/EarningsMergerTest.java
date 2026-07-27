package de.visterion.agora.fetch.earnings;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EarningsMergerTest {

    private static EarningsEvent ev(String sym, String date, String est, String actual) {
        return new EarningsEvent(sym, LocalDate.parse(date),
                est == null ? null : new BigDecimal(est),
                actual == null ? null : new BigDecimal(actual),
                null, null, null);
    }

    @Test void oneDayApartIsTheSameEventAndKeepsTheAnchorDate() {
        var merged = EarningsMerger.merge(List.of(
                List.of(ev("ZZTOP", "2026-07-29", "1.00", null)),   // anchor (order 0)
                List.of(ev("ZZTOP", "2026-07-28", "1.10", null))));

        assertThat(merged).singleElement().satisfies(e -> {
            assertThat(e.date()).isEqualTo(LocalDate.parse("2026-07-29"));
            assertThat(e.epsEstimate()).isEqualByComparingTo("1.00");
        });
    }

    @Test void twoDaysApartAreDistinctEvents() {
        var merged = EarningsMerger.merge(List.of(
                List.of(ev("ZZTOP", "2026-07-29", null, null)),
                List.of(ev("ZZTOP", "2026-07-27", null, null))));

        assertThat(merged).hasSize(2);
    }

    @Test void chainOfThreeDaysClustersAroundTheAnchorNotTransitively() {
        // D, D+1, D+2 — a naive transitive rule would collapse all three into one.
        var merged = EarningsMerger.merge(List.of(
                List.of(ev("ZZTOP", "2026-07-28", null, null)),                 // anchor
                List.of(ev("ZZTOP", "2026-07-29", null, null),
                        ev("ZZTOP", "2026-07-30", null, null))));

        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(EarningsEvent::date)
                .containsExactly(LocalDate.parse("2026-07-28"), LocalDate.parse("2026-07-30"));
    }

    @Test void populatedValueBeatsNullEvenFromALowerPriorityProvider() {
        var merged = EarningsMerger.merge(List.of(
                List.of(ev("ZZTOP", "2026-07-29", null, null)),      // priority, but empty field
                List.of(ev("ZZTOP", "2026-07-29", "1.10", null))));

        assertThat(merged).singleElement()
                .satisfies(e -> assertThat(e.epsEstimate()).isEqualByComparingTo("1.10"));
    }

    @Test void higherPriorityWinsWhenBothArePopulated() {
        var merged = EarningsMerger.merge(List.of(
                List.of(ev("ZZTOP", "2026-07-29", "1.00", null)),
                List.of(ev("ZZTOP", "2026-07-29", "1.10", null))));

        assertThat(merged).singleElement()
                .satisfies(e -> assertThat(e.epsEstimate()).isEqualByComparingTo("1.00"));
    }

    @Test void tickerComparisonIsCaseInsensitive() {
        var merged = EarningsMerger.merge(List.of(
                List.of(ev("ZZTOP", "2026-07-29", null, null)),
                List.of(ev("zztop", "2026-07-29", "1.10", null))));

        assertThat(merged).singleElement()
                .satisfies(e -> assertThat(e.symbol()).isEqualTo("ZZTOP"));
    }

    @Test void unmatchedEventFromALaterProviderBecomesItsOwnEvent() {
        var merged = EarningsMerger.merge(List.of(
                List.of(ev("ZZTOP", "2026-07-29", null, null)),
                List.of(ev("QQTEST", "2026-08-15", null, null))));

        assertThat(merged).extracting(EarningsEvent::symbol)
                .containsExactly("ZZTOP", "QQTEST");
    }

    @Test void resultIsSortedByDateThenSymbol() {
        var merged = EarningsMerger.merge(List.of(List.of(
                ev("ZZTOP", "2026-08-15", null, null),
                ev("AAAA", "2026-07-29", null, null),
                ev("BBBB", "2026-07-29", null, null))));

        assertThat(merged).extracting(EarningsEvent::symbol)
                .containsExactly("AAAA", "BBBB", "ZZTOP");
    }

    @Test void twoGenuineEventsOfOneTickerFarApartBothSurvive() {
        var merged = EarningsMerger.merge(List.of(List.of(
                ev("ZZTOP", "2026-07-29", null, null),
                ev("ZZTOP", "2026-10-29", null, null))));

        assertThat(merged).hasSize(2);
    }

    @Test void emptyInputYieldsEmptyOutput() {
        assertThat(EarningsMerger.merge(List.of(List.of(), List.of()))).isEmpty();
    }
}
