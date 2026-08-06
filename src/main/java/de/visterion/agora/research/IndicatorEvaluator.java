package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.research.IndicatorExpressionResolver.Resolved;
import de.visterion.agora.research.IndicatorExpressionResolver.SpecException;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one symbol's bars plus a list of indicator specs into the per-symbol result object.
 *
 * <p>This is the half of {@code get_indicators} that has nothing to do with argument plumbing or
 * fetching, extracted so {@code get_indicators} and {@code get_indicators_batch} compute the
 * identical thing. Duplicating it would let the two tools drift apart on exactly the values a
 * consumer compares across calls.
 */
@Component
public class IndicatorEvaluator {

    private final IndicatorRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public IndicatorEvaluator(IndicatorRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param specs   array node of indicator specs (already validated as a non-empty array)
     * @param seriesN 0, or how many trailing values to emit per indicator
     * @return {@code {symbol, currentClose, asOf, values[], available}} — {@code available} is
     *         true iff at least one spec produced a value, the same rule the single-symbol tool
     *         has always applied
     */
    public ObjectNode evaluate(String symbol, List<OhlcBar> bars, JsonNode specs, int seriesN) {
        BarSeries series = Ta4jBars.toSeries(bars);
        IndicatorExpressionResolver resolver = new IndicatorExpressionResolver(registry);

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("currentClose", bars.getLast().close());
        out.put("asOf", bars.getLast().date().toString());
        ArrayNode values = out.putArray("values");
        Set<String> usedLabels = new HashSet<>();
        boolean anySpecAvailable = false;

        for (JsonNode specNode : specs) {
            ObjectNode entry = values.addObject();
            try {
                Resolved r = resolver.resolve(specNode, series);
                if (!usedLabels.add(r.label())) {
                    throw new SpecException("duplicate label '" + r.label()
                            + "' — set an explicit label");
                }
                entry.put("label", r.label());
                anySpecAvailable |= writeValues(entry, r, series, seriesN, resolver, specNode);
            } catch (SpecException e) {
                entry.put("available", false);
                entry.put("error", e.getMessage());
            }
        }
        // research low (g): only true if at least one spec actually produced a value.
        out.put("available", anySpecAvailable);
        return out;
    }

    /** A symbol that produced no bars at all: still a full entry, never a silent omission —
     *  the caller must be able to hold requested and returned against each other. */
    public ObjectNode unavailable(String symbol, String reason) {
        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("available", false);
        out.put("error", reason);
        return out;
    }

    /** @return true iff a value was written (entry's own "available" is true). */
    private static boolean writeValues(ObjectNode entry, Resolved r, BarSeries series, int seriesN,
                                        IndicatorExpressionResolver resolver, JsonNode specNode) {
        if (series.getBarCount() < r.minBars()) {
            markInsufficient(entry, r);
            return false;
        }
        int end = series.getEndIndex();
        if (r.def().singleOutput()) {
            String outputKey = r.outputs().keySet().iterator().next();
            Indicator<Num> ind = r.outputs().get(outputKey);
            Num v = ind.getValue(end);
            if (v.isNaN()) {
                // bar count already satisfied minBars — this is a math-domain failure
                // (e.g. division by zero on a flat window), not insufficient history.
                markMathDomainError(entry, r);
                return false;
            }
            entry.put("value", Ta4jBars.toBd(v, 4));
            if (seriesN > 0) {
                writeSeries(entry.putArray("series"), resolver, specNode, series, seriesN,
                        r.minBars(), outputKey);
            }
        } else {
            ObjectNode value = entry.putObject("value");
            boolean any = false;
            for (Map.Entry<String, Indicator<Num>> o : r.outputs().entrySet()) {
                Num v = o.getValue().getValue(end);
                if (v.isNaN()) {
                    value.putNull(o.getKey());
                } else {
                    value.put(o.getKey(), Ta4jBars.toBd(v, 4));
                    any = true;
                }
            }
            if (!any) {
                entry.remove("value");
                markMathDomainError(entry, r);
                return false;
            }
            if (seriesN > 0) {
                ObjectNode seriesObj = entry.putObject("series");
                for (String outputKey : r.outputs().keySet()) {
                    writeSeries(seriesObj.putArray(outputKey), resolver, specNode, series, seriesN,
                            r.minBars(), outputKey);
                }
            }
        }
        entry.put("available", true);
        return true;
    }

    private static void markInsufficient(ObjectNode entry, Resolved r) {
        entry.put("available", false);
        entry.put("error", "insufficient history for " + r.def().name());
    }

    private static void markMathDomainError(ObjectNode entry, Resolved r) {
        entry.put("available", false);
        entry.put("error", "math domain error for " + r.def().name());
    }

    /** H4: ta4j's SMAIndicator (and similar) use a stateful running-total fast path — once a
     *  warm-up NaN enters that running sum, subsequent sequential reads never recover. Re-resolve
     *  the spec fresh for every point (correctness over micro-perf, bounded by MAX_SERIES) so no
     *  indicator instance is ever queried out of the order it was built for. Also starts the
     *  series at the spec's first stable index — no warm-up/partial-window points are emitted. */
    private static void writeSeries(ArrayNode arr, IndicatorExpressionResolver resolver, JsonNode specNode,
                                     BarSeries series, int n, int minBars, String outputKey) {
        int end = series.getEndIndex();
        int from = Math.max(Math.max(series.getBeginIndex(), end - n + 1), minBars - 1);
        for (int i = from; i <= end; i++) {
            Indicator<Num> fresh = resolver.resolve(specNode, series).outputs().get(outputKey);
            Num v = fresh.getValue(i);
            if (v.isNaN()) arr.addNull();
            else arr.add(Ta4jBars.toBd(v, 4));
        }
    }
}
