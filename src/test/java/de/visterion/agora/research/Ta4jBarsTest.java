package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ta4jBarsTest {

    private OhlcBar bar(String d, String o, String h, String l, String c, long v) {
        return new OhlcBar(LocalDate.parse(d), new BigDecimal(o), new BigDecimal(h),
                new BigDecimal(l), new BigDecimal(c), v);
    }

    @Test void buildsSeriesPreservingCountAndPrecision() {
        var bars = List.of(
                bar("2025-01-02", "10.00", "11.00", "9.50", "10.50", 1000),
                bar("2025-01-03", "10.50", "11.50", "10.20", "11.20", 2000));
        BarSeries series = Ta4jBars.toSeries(bars);
        assertThat(series.getBarCount()).isEqualTo(2);
        // last close preserved exactly (DecimalNum, not lossy double)
        var close = new ClosePriceIndicator(series);
        assertThat(Ta4jBars.last(close).bigDecimalValue()).isEqualByComparingTo("11.20");
    }

    @Test void toBdRoundsToScale() {
        var bars = List.of(bar("2025-01-02", "1", "1", "1", "3.333333", 1));
        BarSeries series = Ta4jBars.toSeries(bars);
        var close = new ClosePriceIndicator(series);
        assertThat(Ta4jBars.toBd(Ta4jBars.last(close), 4)).isEqualByComparingTo("3.3333");
    }

    @Test void emptyListYieldsEmptySeries() {
        assertThat(Ta4jBars.toSeries(List.of()).getBarCount()).isZero();
    }

    @Test void lastOnEmptySeriesThrows() {
        var series = Ta4jBars.toSeries(List.of());
        var close = new ClosePriceIndicator(series);
        assertThatThrownBy(() -> Ta4jBars.last(close))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateDateRowsKeepLastAndDoNotThrow() {
        var bars = List.of(
                bar("2025-01-02", "10.00", "11.00", "9.50", "10.50", 1000),
                bar("2025-01-02", "20.00", "21.00", "19.50", "20.50", 2000), // same date, later in list wins
                bar("2025-01-03", "21.00", "22.00", "20.50", "21.50", 3000));
        BarSeries series = Ta4jBars.toSeries(bars);
        assertThat(series.getBarCount()).isEqualTo(2);
        var close = new ClosePriceIndicator(series);
        // first (surviving) bar is the second 2025-01-02 row (last-wins)
        assertThat(close.getValue(0).bigDecimalValue()).isEqualByComparingTo("20.50");
        assertThat(close.getValue(1).bigDecimalValue()).isEqualByComparingTo("21.50");
    }

    @Test void nonAscendingProviderDatesAreSorted() {
        var bars = List.of(
                bar("2025-01-03", "12.00", "13.00", "11.50", "12.50", 1000),
                bar("2025-01-01", "10.00", "11.00", "9.50", "10.50", 1000),
                bar("2025-01-02", "11.00", "12.00", "10.50", "11.50", 1000));
        BarSeries series = Ta4jBars.toSeries(bars);
        assertThat(series.getBarCount()).isEqualTo(3);
        var close = new ClosePriceIndicator(series);
        assertThat(close.getValue(0).bigDecimalValue()).isEqualByComparingTo("10.50");
        assertThat(close.getValue(1).bigDecimalValue()).isEqualByComparingTo("11.50");
        assertThat(close.getValue(2).bigDecimalValue()).isEqualByComparingTo("12.50");
    }

    // -------------------------------------------------------------------------
    // dedupAndSort is part of the public API: the indicator tools (package
    // de.visterion.agora.tools) normalise the provider list with the very same rule the
    // series build uses, so "the last bar" means the same thing in both places.
    // -------------------------------------------------------------------------

    @Test void dedupAndSortIsPublic() throws Exception {
        // getMethod finds public methods only — this is the cross-package contract itself.
        var m = Ta4jBars.class.getMethod("dedupAndSort", List.class);
        assertThat(java.lang.reflect.Modifier.isPublic(m.getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isStatic(m.getModifiers())).isTrue();
    }

    @Test void dedupAndSortKeepsTheLastRowPerDateAndSortsAscending() {
        var bars = List.of(
                bar("2026-09-16", "10.00", "11.00", "9.50", "10.50", 1000),  // superseded
                bar("2026-09-14", "12.00", "13.00", "11.50", "12.50", 2000),
                bar("2026-09-16", "20.00", "21.00", "19.50", "20.50", 3000), // wins for 09-16
                bar("2026-09-15", "14.00", "15.00", "13.50", "14.50", 4000));

        List<OhlcBar> out = Ta4jBars.dedupAndSort(bars);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).date()).isEqualTo(LocalDate.parse("2026-09-14"));
        assertThat(out.get(1).date()).isEqualTo(LocalDate.parse("2026-09-15"));
        assertThat(out.get(2).date()).isEqualTo(LocalDate.parse("2026-09-16"));
        assertThat(out.get(2).close()).isEqualByComparingTo("20.50");
        assertThat(out.get(2).volume()).isEqualTo(3000L);
    }

    @Test void dedupAndSortOnAnEmptyListIsEmpty() {
        assertThat(Ta4jBars.dedupAndSort(List.of())).isEmpty();
    }
}
