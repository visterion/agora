package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
        // A consistent candle: since ta4j 0.25 a bar whose high/low contradict open/close throws.
        var bars = List.of(bar("2025-01-02", "3", "4", "3", "3.333333", 1));
        BarSeries series = Ta4jBars.toSeries(bars);
        var close = new ClosePriceIndicator(series);
        assertThat(Ta4jBars.toBd(Ta4jBars.last(close), 4)).isEqualByComparingTo("3.3333");
    }

    // -------------------------------------------------------------------------
    // OHLC invariant. Since ta4j 0.25 BaseBar rejects a candle whose high is below its open or
    // close (or whose low is above them). Real provider data does contain such rows — e.g.
    // free-feed daily bars around exchange auctions/holidays where the reported high/low do not
    // cover the open/close. toSeries widens high/low to the open/close envelope instead of
    // letting one bad row fail the whole indicator call. Fixtures are invented by hand.
    // -------------------------------------------------------------------------

    @Test void ta4jRejectsAnInconsistentCandle() {
        // Documents the library contract the repair below exists for.
        BarSeries series = new BaseBarSeriesBuilder().withName("contract").build();
        assertThatThrownBy(() -> series.barBuilder()
                .endTime(Instant.parse("2025-01-03T00:00:00Z"))
                .timePeriod(Duration.ofDays(1))
                .openPrice("50.00").highPrice("49.40").lowPrice("49.35").closePrice("49.42")
                .volume("1000")
                .add())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("High price must be greater than or equal to open price");
    }

    @Test void highBelowOpenAndCloseIsWidenedToTheOpenCloseEnvelope() {
        var bars = List.of(
                bar("2025-01-02", "49.00", "50.10", "48.90", "49.90", 1000),
                bar("2025-01-03", "50.00", "49.40", "49.35", "49.42", 2000), // high < open, high < close
                bar("2025-01-06", "49.50", "49.80", "49.10", "49.60", 3000));

        BarSeries series = Ta4jBars.toSeries(bars);

        assertThat(series.getBarCount()).isEqualTo(3);                 // no row dropped
        assertThat(new OpenPriceIndicator(series).getValue(1).bigDecimalValue()).isEqualByComparingTo("50.00");
        assertThat(new HighPriceIndicator(series).getValue(1).bigDecimalValue()).isEqualByComparingTo("50.00");
        assertThat(new LowPriceIndicator(series).getValue(1).bigDecimalValue()).isEqualByComparingTo("49.35");
        assertThat(new ClosePriceIndicator(series).getValue(1).bigDecimalValue()).isEqualByComparingTo("49.42");
    }

    @Test void lowAboveCloseIsWidenedDownToTheClose() {
        var bars = List.of(bar("2025-01-02", "100.00", "101.20", "99.50", "99.10", 1000)); // low > close

        BarSeries series = Ta4jBars.toSeries(bars);

        assertThat(new LowPriceIndicator(series).getValue(0).bigDecimalValue()).isEqualByComparingTo("99.10");
        assertThat(new HighPriceIndicator(series).getValue(0).bigDecimalValue()).isEqualByComparingTo("101.20");
        assertThat(Ta4jBars.last(new ClosePriceIndicator(series)).bigDecimalValue()).isEqualByComparingTo("99.10");
    }

    @Test void highBelowLowIsWidenedToCoverAllFourPrices() {
        var bars = List.of(bar("2025-01-02", "10.00", "9.80", "10.20", "10.10", 1000)); // high < low

        BarSeries series = Ta4jBars.toSeries(bars);

        assertThat(new HighPriceIndicator(series).getValue(0).bigDecimalValue()).isEqualByComparingTo("10.20");
        assertThat(new LowPriceIndicator(series).getValue(0).bigDecimalValue()).isEqualByComparingTo("9.80");
    }

    @Test void consistentBarsPassThroughUnchanged() {
        var bars = List.of(bar("2025-01-02", "10.00", "11.00", "9.50", "10.50", 1000));

        BarSeries series = Ta4jBars.toSeries(bars);

        assertThat(new HighPriceIndicator(series).getValue(0).bigDecimalValue()).isEqualByComparingTo("11.00");
        assertThat(new LowPriceIndicator(series).getValue(0).bigDecimalValue()).isEqualByComparingTo("9.50");
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
