package de.visterion.agora.research;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.aroon.AroonDownIndicator;
import org.ta4j.core.indicators.aroon.AroonOscillatorIndicator;
import org.ta4j.core.indicators.aroon.AroonUpIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.indicators.helpers.TRIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuChikouSpanIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuKijunSenIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuSenkouSpanAIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuSenkouSpanBIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuTenkanSenIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
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
        return List.of(atr(), chandelierStop(), maCross(), fiftyTwoWeekRange(),
                macd(), bollinger(), stochastic(), aroon(), ichimoku());
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

    private static IndicatorDef macd() {
        return new IndicatorDef("macd", "MACD line, signal line and histogram",
                List.of(ParamDef.intParam("fast", 12, 1, 500),
                        ParamDef.intParam("slow", 26, 2, 1000),
                        ParamDef.intParam("signal", 9, 1, 500)),
                1, List.of("macd", "signal", "histogram"),
                p -> p.getInt("slow") + p.getInt("signal"),
                (series, inputs, p) -> {
                    int fast = p.getInt("fast");
                    int slow = p.getInt("slow");
                    if (slow <= fast) throw new IllegalArgumentException("slow must be greater than fast");
                    var macd = new MACDIndicator(inputs[0], fast, slow);
                    var signal = new EMAIndicator(macd, p.getInt("signal"));
                    var outs = new LinkedHashMap<String, Indicator<Num>>();
                    outs.put("macd", macd);
                    outs.put("signal", signal);
                    outs.put("histogram", new CombineIndicator(macd, signal, Num::minus));
                    return outs;
                });
    }

    private static IndicatorDef bollinger() {
        return new IndicatorDef("bollinger", "Bollinger Bands (upper/middle/lower)",
                List.of(ParamDef.intParam("period", 20, 2, 500),
                        ParamDef.decimalParam("k", "2.0", "0.1", "10")),
                1, List.of("upper", "middle", "lower"),
                p -> p.getInt("period"),
                (series, inputs, p) -> {
                    int period = p.getInt("period");
                    Num k = Ta4jBars.num(series, p.getDecimal("k"));
                    var middle = new BollingerBandsMiddleIndicator(new SMAIndicator(inputs[0], period));
                    var sd = new StandardDeviationIndicator(inputs[0], period);
                    var outs = new LinkedHashMap<String, Indicator<Num>>();
                    outs.put("upper", new BollingerBandsUpperIndicator(middle, sd, k));
                    outs.put("middle", middle);
                    outs.put("lower", new BollingerBandsLowerIndicator(middle, sd, k));
                    return outs;
                });
    }

    private static IndicatorDef stochastic() {
        return new IndicatorDef("stochastic", "Stochastic oscillator %K and %D",
                List.of(ParamDef.intParam("k", 14, 1, 500),
                        ParamDef.intParam("d", 3, 1, 100)),
                0, List.of("k", "d"),
                p -> p.getInt("k") + p.getInt("d"),
                (series, inputs, p) -> {
                    var k = new StochasticOscillatorKIndicator(series, p.getInt("k"));
                    var outs = new LinkedHashMap<String, Indicator<Num>>();
                    outs.put("k", k);
                    outs.put("d", new SMAIndicator(k, p.getInt("d")));
                    return outs;
                });
    }

    private static IndicatorDef aroon() {
        return new IndicatorDef("aroon", "Aroon up, down and oscillator",
                List.of(ParamDef.intParam("period", 25, 1, 500)),
                0, List.of("up", "down", "oscillator"),
                p -> p.getInt("period") + 1,
                (series, inputs, p) -> {
                    int period = p.getInt("period");
                    var outs = new LinkedHashMap<String, Indicator<Num>>();
                    outs.put("up", new AroonUpIndicator(series, period));
                    outs.put("down", new AroonDownIndicator(series, period));
                    outs.put("oscillator", new AroonOscillatorIndicator(series, period));
                    return outs;
                });
    }

    private static IndicatorDef ichimoku() {
        return new IndicatorDef("ichimoku",
                "Ichimoku cloud lines (standard 9/26/52 settings)",
                List.of(),
                0, List.of("tenkan", "kijun", "senkou_a", "senkou_b", "chikou"),
                p -> 52,
                (series, inputs, p) -> {
                    var outs = new LinkedHashMap<String, Indicator<Num>>();
                    outs.put("tenkan", new IchimokuTenkanSenIndicator(series));
                    outs.put("kijun", new IchimokuKijunSenIndicator(series));
                    outs.put("senkou_a", new IchimokuSenkouSpanAIndicator(series));
                    outs.put("senkou_b", new IchimokuSenkouSpanBIndicator(series));
                    outs.put("chikou", new IchimokuChikouSpanIndicator(series));
                    return outs;
                });
    }
}
