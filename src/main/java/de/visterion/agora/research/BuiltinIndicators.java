package de.visterion.agora.research;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.DPOIndicator;
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
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.indicators.helpers.PreviousValueIndicator;
import org.ta4j.core.indicators.helpers.TRIndicator;
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
 *  ma_cross/52w_range values reproduce IndicatorService.compute() exactly (parity-guarded,
 *  see BuiltinIndicatorsParityTest) — including chandelier_stop, which both this class and
 *  IndicatorService compute from Wilder-smoothed (MMA) ATR, not SMA-of-TR (research low (h)). */
public final class BuiltinIndicators {

    private BuiltinIndicators() {}

    /** 52w_range windows to the last 252 trading days (research low (a)) — not the entire
     *  fetched history, which used to grow the "52-week" window unboundedly with fetchDays. */
    private static final int FIFTY_TWO_WEEK_WINDOW = 252;

    public static List<IndicatorDef> defs() {
        return List.of(atr(), chandelierStop(), maCross(), fiftyTwoWeekRange(),
                macd(), bollinger(), stochastic(), aroon(), ichimoku(), dpo());
    }

    /** SMA of True Range — the slice-3 formula, NOT Wilder's ATRIndicator. Used only by the
     *  standalone 'atr' entry; chandelier_stop uses Wilder-smoothed ATR (see chandelierStop()). */
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
                "Chandelier stop (long-only): highest high(period) minus multiple * "
                        + "Wilder-smoothed ATR(period) (MMA, not the SMA-ATR the 'atr' entry uses)",
                List.of(ParamDef.intParam("period", 22, 1, 500),
                        ParamDef.decimalParam("multiple", "3.0", "0.1", "100")),
                0, List.of("value"),
                // H3-style: Wilder MMA is a recursive filter, seeded with the raw first TR value —
                // convergence-safe minBars, not period+1.
                p -> 1 + 4 * p.getInt("period"),
                (series, inputs, p) -> {
                    int period = p.getInt("period");
                    Num mult = Ta4jBars.num(series, p.getDecimal("multiple"));
                    var hh = new HighestValueIndicator(new HighPriceIndicator(series), period);
                    var atr = new ATRIndicator(series, period);
                    return Map.of("value",
                            new CombineIndicator(hh, atr, (h, a) -> h.minus(a.multipliedBy(mult))));
                });
    }

    private static IndicatorDef dpo() {
        // research low (b): ta4j's DPOIndicator (Price[t-(period/2+1)] - SMA(period)[t]) is
        // unstable for period + period/2 bars, not period+1 like the generic YAML formula assumes —
        // that nonlinearity is why dpo lives here instead of indicators-catalog.yaml.
        return new IndicatorDef("dpo", "Detrended Price Oscillator",
                List.of(ParamDef.intParam("period", 20, 1, 500)),
                1, List.of("value"),
                p -> p.getInt("period") + p.getInt("period") / 2 + 1,
                (series, inputs, p) -> Map.of("value", new DPOIndicator(inputs[0], p.getInt("period"))));
    }

    private static IndicatorDef maCross() {
        return new IndicatorDef("ma_cross",
                "Fast and slow simple moving averages (compare fast vs slow for cross state)",
                List.of(ParamDef.intParam("fast", 50, 1, 500),
                        ParamDef.intParam("slow", 200, 2, 1000)),
                1, List.of("fast", "slow"),
                p -> p.getInt("slow"),
                // no "+1": minBars IS the raw window (the slow SMA needs exactly `slow`
                // input points) — explicit rawWindow so composition (e.g. ma_cross-of-rsi)
                // doesn't under-count via the default (minBars - 1) convention.
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
                    int window = Math.max(1, Math.min(FIFTY_TWO_WEEK_WINDOW, series.getBarCount()));
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
                // H3: the signal line is an EMAIndicator (recursive filter, same class RSI/EMA
                // got the 4x treatment) — convergence-safe minBars, not the exact slow+signal
                // window. maxPeriod for MACD is slow+signal (research brief).
                p -> 1 + 4 * (p.getInt("slow") + p.getInt("signal")),
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
                // no "+1": minBars IS the raw window (SMA/stddev need exactly `period`
                // input points) — explicit rawWindow, see ma_cross() above for rationale.
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
                // research low (b): senkou span B (52-period highest/lowest) is the true
                // limiting output — ta4j reports it unstable for 76 bars (not the old, too-low 52).
                p -> 77,
                (series, inputs, p) -> {
                    var outs = new LinkedHashMap<String, Indicator<Num>>();
                    outs.put("tenkan", new IchimokuTenkanSenIndicator(series));
                    outs.put("kijun", new IchimokuKijunSenIndicator(series));
                    outs.put("senkou_a", new IchimokuSenkouSpanAIndicator(series));
                    outs.put("senkou_b", new IchimokuSenkouSpanBIndicator(series));
                    // ta4j's IchimokuChikouSpanIndicator plots close 26 bars FORWARD, so it is
                    // NaN at the newest bar by construction. Consumers want the line's latest
                    // defined point instead: close from 26 bars ago (what chart platforms show).
                    outs.put("chikou", new PreviousValueIndicator(new ClosePriceIndicator(series), 26));
                    return outs;
                });
    }
}
