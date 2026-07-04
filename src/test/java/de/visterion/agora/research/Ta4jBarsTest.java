package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
