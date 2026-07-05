package de.visterion.agora.research;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

import java.util.Map;

/** Builds the ta4j indicator(s) for one catalog entry. Map keys must match
 *  IndicatorDef.outputs(); multi-output factories must return a LinkedHashMap
 *  so output order is stable. */
@FunctionalInterface
public interface IndicatorFactory {
    Map<String, Indicator<Num>> create(BarSeries series, Indicator<Num>[] inputs, ResolvedParams params);
}
