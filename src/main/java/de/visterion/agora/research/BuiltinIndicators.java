package de.visterion.agora.research;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.indicators.helpers.TRIndicator;
import org.ta4j.core.num.Num;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Java-coded catalog entries: composites and multi-output indicators that cannot be
 *  expressed as one-class-one-constructor YAML entries. The atr/chandelier_stop/
 *  ma_cross/52w_range values reproduce IndicatorService exactly (parity-guarded). */
public final class BuiltinIndicators {

    private BuiltinIndicators() {}

    public static List<IndicatorDef> defs() {
        return List.of(atr(), chandelierStop(), maCross(), fiftyTwoWeekRange());
    }

    /** SMA of True Range — the slice-3 formula, NOT Wilder's ATRIndicator. */
    private static SMAIndicator smaOfTr(BarSeries series, int period) {
        return new SMAIndicator(new TRIndicator(series), period);
    }

    private static IndicatorDef atr() {
        return new IndicatorDef("atr", "Average True Range (SMA of True Range)",
                List.of(ParamDef.intParam("period", 22, 1, 500)),
                0, List.of("value"),
                p -> p.getInt("period") + 1,
                (series, inputs, p) -> Map.of("value", smaOfTr(series, p.getInt("period"))));
    }

    private static IndicatorDef chandelierStop() {
        return new IndicatorDef("chandelier_stop",
                "Chandelier stop: highest high(period) minus multiple * ATR(period)",
                List.of(ParamDef.intParam("period", 22, 1, 500),
                        ParamDef.decimalParam("multiple", "3.0", "0.1", "100")),
                0, List.of("value"),
                p -> p.getInt("period") + 1,
                (series, inputs, p) -> {
                    int period = p.getInt("period");
                    Num mult = Ta4jBars.num(series, p.getDecimal("multiple"));
                    var hh = new HighestValueIndicator(new HighPriceIndicator(series), period);
                    var atr = smaOfTr(series, period);
                    return Map.of("value",
                            new CombineIndicator(hh, atr, (h, a) -> h.minus(a.multipliedBy(mult))));
                });
    }

    private static IndicatorDef maCross() {
        return new IndicatorDef("ma_cross",
                "Fast and slow simple moving averages (compare fast vs slow for cross state)",
                List.of(ParamDef.intParam("fast", 50, 1, 500),
                        ParamDef.intParam("slow", 200, 2, 1000)),
                1, List.of("fast", "slow"),
                p -> p.getInt("slow"),
                (series, inputs, p) -> {
                    int fast = p.getInt("fast");
                    int slow = p.getInt("slow");
                    if (slow <= fast) throw new IllegalArgumentException("slow must be greater than fast");
                    var outs = new LinkedHashMap<String, Indicator<Num>>();
                    outs.put("fast", new SMAIndicator(inputs[0], fast));
                    outs.put("slow", new SMAIndicator(inputs[0], slow));
                    return outs;
                });
    }

    private static IndicatorDef fiftyTwoWeekRange() {
        return new IndicatorDef("52w_range",
                "Highest high and lowest low over the fetched history (52-week range)",
                List.of(ParamDef.intParam("minBars", 250, 1, 1000)),
                0, List.of("high", "low"),
                p -> p.getInt("minBars"),
                (series, inputs, p) -> {
                    int window = Math.max(1, series.getBarCount());
                    var outs = new LinkedHashMap<String, Indicator<Num>>();
                    outs.put("high", new HighestValueIndicator(new HighPriceIndicator(series), window));
                    outs.put("low", new LowestValueIndicator(new LowPriceIndicator(series), window));
                    return outs;
                });
    }
}
