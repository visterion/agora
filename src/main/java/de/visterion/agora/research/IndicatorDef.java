package de.visterion.agora.research;

import java.util.List;
import java.util.function.ToIntFunction;

/** One indicator-catalog entry. inputs: 0 = works on the BarSeries itself (needs
 *  high/low/close/volume together, e.g. ATR); 1 = takes one sub-indicator (composable,
 *  e.g. RSI/SMA). minBars: bars required before the value counts as available. */
public record IndicatorDef(
        String name,
        String description,
        List<ParamDef> params,
        int inputs,
        List<String> outputs,
        ToIntFunction<ResolvedParams> minBars,
        IndicatorFactory factory) {

    public IndicatorDef {
        params = List.copyOf(params);
        outputs = List.copyOf(outputs);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("indicator name required");
        if (inputs < 0 || inputs > 1) throw new IllegalArgumentException("inputs must be 0 or 1");
        if (outputs.isEmpty()) throw new IllegalArgumentException("outputs required");
    }

    public boolean singleOutput() { return outputs.size() == 1; }
}
