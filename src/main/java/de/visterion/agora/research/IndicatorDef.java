package de.visterion.agora.research;

import java.util.List;
import java.util.function.ToIntFunction;

/** One indicator-catalog entry. inputs: 0 = works on the BarSeries itself (needs
 *  high/low/close/volume together, e.g. ATR); 1 = takes one sub-indicator (composable,
 *  e.g. RSI/SMA). minBars: bars required before the value counts as available.
 *  rawWindow: how many points of THIS indicator's input are needed to produce its first
 *  stable value — used by IndicatorExpressionResolver to compose minBars additively when
 *  this indicator is the outer of a chain (e.g. {"name":"bollinger","of":"rsi"}). */
public record IndicatorDef(
        String name,
        String description,
        List<ParamDef> params,
        int inputs,
        List<String> outputs,
        ToIntFunction<ResolvedParams> minBars,
        ToIntFunction<ResolvedParams> rawWindow,
        IndicatorFactory factory) {

    public IndicatorDef {
        params = List.copyOf(params);
        outputs = List.copyOf(outputs);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("indicator name required");
        if (inputs < 0 || inputs > 1) throw new IllegalArgumentException("inputs must be 0 or 1");
        if (outputs.isEmpty()) throw new IllegalArgumentException("outputs required");
    }

    /** Convenience constructor for the common "minBars = 1 + rawWindow" convention that
     *  every YAML-loaded entry and most Java entries follow (rawWindow defaults to
     *  minBars - 1). Indicators whose minBars does NOT follow that convention (e.g.
     *  bollinger's minBars == period, ma_cross's minBars == slow — no "+1") MUST use the
     *  full constructor and supply an explicit rawWindow, or additive composition in
     *  IndicatorExpressionResolver will under-count when this indicator is used as the
     *  outer of a chain. */
    public IndicatorDef(String name, String description, List<ParamDef> params, int inputs,
                         List<String> outputs, ToIntFunction<ResolvedParams> minBars,
                         IndicatorFactory factory) {
        this(name, description, params, inputs, outputs, minBars,
                p -> minBars.applyAsInt(p) - 1, factory);
    }

    public boolean singleOutput() { return outputs.size() == 1; }
}
