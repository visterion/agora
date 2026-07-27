package de.visterion.agora.fetch.earnings;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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

    @Test void clusteringIsDeterministicIndependentOfProviderEventOrder() {
        // One provider, ascending order
        var ascending = EarningsMerger.merge(List.of(
                List.of(
                        ev("ZZTOP", "2026-07-28", null, null),
                        ev("ZZTOP", "2026-07-29", "2.00", null),
                        ev("ZZTOP", "2026-07-30", "3.00", null))));

        // Same provider, descending order
        var descending = EarningsMerger.merge(List.of(
                List.of(
                        ev("ZZTOP", "2026-07-30", "3.00", null),
                        ev("ZZTOP", "2026-07-29", "2.00", null),
                        ev("ZZTOP", "2026-07-28", null, null))));

        assertThat(ascending).hasSize(2);
        assertThat(descending).hasSize(2);
        assertThat(ascending).extracting(EarningsEvent::date)
                .containsExactly(LocalDate.parse("2026-07-28"), LocalDate.parse("2026-07-30"));
        assertThat(descending).extracting(EarningsEvent::date)
                .containsExactly(LocalDate.parse("2026-07-28"), LocalDate.parse("2026-07-30"));
        // Both should have D+1 merged into D, so D should have epsEstimate=2.00
        assertThat(ascending.get(0).epsEstimate()).isEqualByComparingTo("2.00");
        assertThat(descending.get(0).epsEstimate()).isEqualByComparingTo("2.00");
    }

    @Test void equidistantEventsTieTowardEarlierDate() {
        // Clusters at D and D+2, event at D+1 should join D (earlier), not D+2
        var merged = EarningsMerger.merge(List.of(
                List.of(
                        ev("ZZTOP", "2026-07-28", "1.00", null),
                        ev("ZZTOP", "2026-07-30", "3.00", null)),
                List.of(
                        ev("ZZTOP", "2026-07-29", "2.00", null))));

        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(EarningsEvent::date)
                .containsExactly(LocalDate.parse("2026-07-28"), LocalDate.parse("2026-07-30"));
        // D+1 should join D (earlier date), not D+2
        assertThat(merged.get(0).epsEstimate()).isEqualByComparingTo("1.00");
        assertThat(merged.get(1).epsEstimate()).isEqualByComparingTo("3.00");
    }

    @Test void mergeNullOuterListYieldsEmpty() {
        assertThat(EarningsMerger.merge(null)).isEmpty();
    }

    @Test void providerListWithLiteralNullElementIsSkipped() {
        var events = new ArrayList<EarningsEvent>();
        events.add(ev("ZZTOP", "2026-07-29", "1.00", null));
        events.add(null);  // literal null element
        events.add(ev("ZZTOP", "2026-07-30", "2.00", null));

        var merged = EarningsMerger.merge(List.of(events));

        // Valid events still merge normally (1 day apart)
        assertThat(merged).singleElement()
                .satisfies(e -> assertThat(e.date()).isEqualTo(LocalDate.parse("2026-07-29")));
    }

    @Test void eventWithNullDateIsSkipped() {
        var events = new ArrayList<EarningsEvent>();
        events.add(ev("ZZTOP", "2026-07-29", "1.00", null));
        events.add(new EarningsEvent("ZZTOP", null, new BigDecimal("1.50"), null, null, null, null));
        events.add(ev("ZZTOP", "2026-08-01", "2.00", null));  // 3 days apart, won't merge with first

        var merged = EarningsMerger.merge(List.of(events));

        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(EarningsEvent::date)
                .containsExactly(LocalDate.parse("2026-07-29"), LocalDate.parse("2026-08-01"));
    }

    @Test void eventWithNullSymbolIsSkipped() {
        var events = new ArrayList<EarningsEvent>();
        events.add(ev("ZZTOP", "2026-07-29", "1.00", null));
        events.add(new EarningsEvent(null, LocalDate.parse("2026-07-30"), new BigDecimal("1.50"), null, null, null, null));
        events.add(ev("ZZTOP", "2026-08-01", "2.00", null));  // 3 days apart, won't merge with first

        var merged = EarningsMerger.merge(List.of(events));

        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(EarningsEvent::date)
                .containsExactly(LocalDate.parse("2026-07-29"), LocalDate.parse("2026-08-01"));
    }
}
