package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinIndicatorsTest {

    /** N bars, close == i+1 (rising), high=close+1, low=close-1. */
    private List<OhlcBar> rising(int n) {
        var bars = new ArrayList<OhlcBar>();
        for (int i = 0; i < n; i++) {
            var c = new BigDecimal(i + 1);
            bars.add(new OhlcBar(LocalDate.of(2025, 1, 1).plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000));
        }
        return bars;
    }

    private static IndicatorDef find(String name) {
        return BuiltinIndicators.defs().stream()
                .filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Indicator<Num>[] in(Indicator<Num> i) { return new Indicator[]{i}; }

    @SuppressWarnings("unchecked")
    private static Indicator<Num>[] none() { return new Indicator[0]; }

    private static BigDecimal at(Map<String, Indicator<Num>> outs, String key, BarSeries s) {
        return Ta4jBars.toBd(outs.get(key).getValue(s.getEndIndex()), 4);
    }

    @Test
    void catalogHasTenEntries() {
        assertThat(BuiltinIndicators.defs()).extracting(IndicatorDef::name)
                .containsExactlyInAnyOrder("atr", "chandelier_stop", "ma_cross", "52w_range",
                        "macd", "bollinger", "stochastic", "aroon", "ichimoku", "dpo");
    }

    @Test
    void dpoTrueFullWindowMinBars() {
        // research low (b): ta4j's DPOIndicator is unstable for period + period/2 bars
        // (SMA(period) shifted back period/2+1), not period+1.
        var def = find("dpo");
        assertThat(def.minBars().applyAsInt(ResolvedParams.defaults(def.params())))
                .isEqualTo(20 + 20 / 2 + 1);

        var series = Ta4jBars.toSeries(rising(31));
        var outs = def.factory().create(series, in(new ClosePriceIndicator(series)),
                ResolvedParams.defaults(def.params()));
        assertThat(outs.get("value").getValue(series.getEndIndex()).isNaN()).isFalse();
    }

    @Test
    void chandelierUsesWilderSmoothedAtrNotSma() {
        // research low (h): chandelier must use Wilder-smoothed (MMA) ATR, not SMA-ATR.
        var series = Ta4jBars.toSeries(rising(100));
        var def = find("chandelier_stop");
        var outs = def.factory().create(series, none(), ResolvedParams.defaults(def.params()));
        var expectedHH = new org.ta4j.core.indicators.helpers.HighestValueIndicator(
                new org.ta4j.core.indicators.helpers.HighPriceIndicator(series), 22);
        var expectedAtr = new org.ta4j.core.indicators.ATRIndicator(series, 22);
        int end = series.getEndIndex();
        var expected = Ta4jBars.toBd(
                expectedHH.getValue(end).minus(expectedAtr.getValue(end).multipliedBy(
                        series.numFactory().numOf(new BigDecimal("3.0")))), 4);
        assertThat(at(outs, "value", series)).isEqualByComparingTo(expected);

        // convergence-safe minBars for the now-recursive Wilder ATR (H3-style, period=22)
        assertThat(def.minBars().applyAsInt(ResolvedParams.defaults(def.params())))
                .isEqualTo(1 + 4 * 22);
    }

    @Test
    void ichimokuMinBarsCoversSenkouSpanB() {
        // research low (b): senkou span B (52-period highest/lowest, shifted 26 forward) is
        // the limiting output; old minBars=52 reported it "available" while still a partial window.
        var def = find("ichimoku");
        assertThat(def.minBars().applyAsInt(ResolvedParams.defaults(def.params()))).isEqualTo(77);
    }

    @Test
    void macdMatchesDirectTa4jComposition() {
        var series = Ta4jBars.toSeries(rising(60));
        var close = new ClosePriceIndicator(series);
        var def = find("macd");
        var outs = def.factory().create(series, in(close), ResolvedParams.defaults(def.params()));
        int end = series.getEndIndex();

        var expectedMacd = new MACDIndicator(close, 12, 26);
        var expectedSignal = new EMAIndicator(expectedMacd, 9);
        assertThat(at(outs, "macd", series))
                .isEqualByComparingTo(Ta4jBars.toBd(expectedMacd.getValue(end), 4));
        assertThat(at(outs, "signal", series))
                .isEqualByComparingTo(Ta4jBars.toBd(expectedSignal.getValue(end), 4));
        assertThat(at(outs, "histogram", series)).isEqualByComparingTo(
                Ta4jBars.toBd(expectedMacd.getValue(end).minus(expectedSignal.getValue(end)), 4));
        // rising closes: fast EMA above slow EMA → macd positive (guards against swapped args)
        assertThat(at(outs, "macd", series)).isPositive();
        assertThat(def.minBars().applyAsInt(ResolvedParams.defaults(def.params()))).isEqualTo(35);
    }

    @Test
    void bollingerOrdering() {
        var series = Ta4jBars.toSeries(rising(30));
        var close = new ClosePriceIndicator(series);
        var def = find("bollinger");
        var outs = def.factory().create(series, in(close), ResolvedParams.defaults(def.params()));
        var upper = at(outs, "upper", series);
        var middle = at(outs, "middle", series);
        var lower = at(outs, "lower", series);
        assertThat(upper).isGreaterThan(middle);
        assertThat(middle).isGreaterThan(lower);
        // middle == SMA(close, 20) on closes 11..30 = 20.5
        assertThat(middle).isEqualByComparingTo("20.5");
    }

    @Test
    void stochasticBounds() {
        var series = Ta4jBars.toSeries(rising(30));
        var def = find("stochastic");
        var outs = def.factory().create(series, none(), ResolvedParams.defaults(def.params()));
        var k = at(outs, "k", series);
        var d = at(outs, "d", series);
        assertThat(k).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(d).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
    }

    @Test
    void aroonOnRisingBars() {
        var series = Ta4jBars.toSeries(rising(40));
        var def = find("aroon");
        var outs = def.factory().create(series, none(), ResolvedParams.defaults(def.params()));
        // strictly rising: newest bar is the highest high → aroon up = 100, down = 0
        assertThat(at(outs, "up", series)).isEqualByComparingTo("100");
        assertThat(at(outs, "down", series)).isEqualByComparingTo("0");
        assertThat(at(outs, "oscillator", series)).isEqualByComparingTo("100");
    }

    @Test
    void ichimokuCoreLinesComputable() {
        var series = Ta4jBars.toSeries(rising(120));
        var def = find("ichimoku");
        var outs = def.factory().create(series, none(), ResolvedParams.defaults(def.params()));
        int end = series.getEndIndex();
        // tenkan = (highest high(9) + lowest low(9)) / 2; closes 112..120 → highs 113..121, lows 111..119
        // = (121 + 111) / 2 = 116
        assertThat(at(outs, "tenkan", series)).isEqualByComparingTo("116");
        // kijun = (highest high(26) + lowest low(26)) / 2 = (121 + 94) / 2 = 107.5
        assertThat(at(outs, "kijun", series)).isEqualByComparingTo("107.5");
        // senkou spans exist and are numeric at end (projection semantics vary; NaN allowed for chikou)
        assertThat(outs.get("senkou_a").getValue(end)).isNotNull();
        assertThat(outs.get("senkou_b").getValue(end)).isNotNull();
        // chikou = close lagged 26 bars: rising(120) has close(i) = i+1, end index 119
        // -> close(93) = 94
        assertThat(at(outs, "chikou", series)).isEqualByComparingTo("94");
        assertThat(def.outputs()).containsExactly("tenkan", "kijun", "senkou_a", "senkou_b", "chikou");
    }

    @Test
    void fiftyTwoWeekRangeWindowsToLast252BarsNotFullHistory() {
        // research low (a): 400 rising bars — full-history high/low would be 401/399 (last bar).
        // Windowed to 252, high/low come from the last 252 bars only: close 149..400 (0-based i=148..399).
        var series = Ta4jBars.toSeries(rising(400));
        var def = find("52w_range");
        var outs = def.factory().create(series, none(),
                ResolvedParams.defaults(def.params()));
        // rising(400): close(i) = i+1, high = close+1, low = close-1. Last 252 bars: i=148..399
        // -> closes 149..400 -> highs 150..401, lows 148..399
        assertThat(at(outs, "high", series)).isEqualByComparingTo("401");
        assertThat(at(outs, "low", series)).isEqualByComparingTo("148");
    }

    @Test
    void ichimokuChikouIsNaNOnShortHistory() {
        var series = Ta4jBars.toSeries(rising(20));   // fewer than 27 bars
        var def = find("ichimoku");
        var outs = def.factory().create(series, none(), ResolvedParams.defaults(def.params()));
        assertThat(outs.get("chikou").getValue(series.getEndIndex()).isNaN()).isTrue();
    }
}
