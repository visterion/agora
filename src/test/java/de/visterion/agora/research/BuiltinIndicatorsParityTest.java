package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards that the registry entries for atr/chandelier_stop/ma_cross/52w_range
 *  reproduce IndicatorService.compute exactly (the migration is value-preserving). */
class BuiltinIndicatorsParityTest {

    // Same small params as IndicatorServiceTest: atr 3, multiple 3.0, ma 2/4, 52w 5
    private static final IndicatorService.Params SMALL =
            new IndicatorService.Params(3, new BigDecimal("3.0"), 2, 4, 5);

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
    private static Indicator<Num>[] none() { return new Indicator[0]; }

    @SuppressWarnings("unchecked")
    private static Indicator<Num>[] in(Indicator<Num> i) { return new Indicator[]{i}; }

    private static ResolvedParams p(Map<String, String> kv) {
        var m = new java.util.LinkedHashMap<String, BigDecimal>();
        kv.forEach((k, v) -> m.put(k, new BigDecimal(v)));
        return new ResolvedParams(m);
    }

    private static BigDecimal at(Map<String, Indicator<Num>> outs, String key, BarSeries s) {
        return Ta4jBars.toBd(outs.get(key).getValue(s.getEndIndex()), 4);
    }

    @Test
    void parityWithIndicatorService() {
        var bars = rising(6);
        var series = Ta4jBars.toSeries(bars);
        var expected = new IndicatorService(SMALL).compute(bars);

        var atr = find("atr").factory().create(series, none(), p(Map.of("period", "3")));
        assertThat(at(atr, "value", series)).isEqualByComparingTo(expected.atr());

        var ch = find("chandelier_stop").factory()
                .create(series, none(), p(Map.of("period", "3", "multiple", "3.0")));
        assertThat(at(ch, "value", series)).isEqualByComparingTo(expected.chandelierStop());

        var ma = find("ma_cross").factory()
                .create(series, in(new ClosePriceIndicator(series)), p(Map.of("fast", "2", "slow", "4")));
        assertThat(at(ma, "fast", series)).isEqualByComparingTo(expected.maFast());
        assertThat(at(ma, "slow", series)).isEqualByComparingTo(expected.maSlow());

        var range = find("52w_range").factory()
                .create(series, none(), p(Map.of("minBars", "5")));
        assertThat(at(range, "high", series)).isEqualByComparingTo(expected.high52w());
        assertThat(at(range, "low", series)).isEqualByComparingTo(expected.low52w());
    }

    @Test
    void minBarsMatchOldAvailabilityRules() {
        // old: atrAvailable iff (n-1) >= period  →  minBars = period + 1
        // 'atr' itself is unchanged (SMA of TR, still exact). 'chandelier_stop' now uses
        // Wilder-smoothed (recursive) ATR (research low (h)) so it needs the H3 convergence-safe
        // minBars (1 + 4*period), not the old exact period+1 — see BuiltinIndicatorsTest.
        assertThat(find("atr").minBars().applyAsInt(p(Map.of("period", "3")))).isEqualTo(4);
        assertThat(find("chandelier_stop").minBars()
                .applyAsInt(p(Map.of("period", "3", "multiple", "3.0")))).isEqualTo(1 + 4 * 3);
        // old: maSlowAvailable iff n >= maSlow
        assertThat(find("ma_cross").minBars().applyAsInt(p(Map.of("fast", "2", "slow", "4")))).isEqualTo(4);
        // old: window52wAvailable iff n >= minBarsFor52w
        assertThat(find("52w_range").minBars().applyAsInt(p(Map.of("minBars", "5")))).isEqualTo(5);
    }

    @Test
    void maCrossRejectsFastNotBelowSlow() {
        var series = Ta4jBars.toSeries(rising(6));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                find("ma_cross").factory().create(series, in(new ClosePriceIndicator(series)),
                        p(Map.of("fast", "4", "slow", "4"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
