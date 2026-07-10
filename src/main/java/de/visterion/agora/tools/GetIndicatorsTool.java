package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.research.IndicatorExpressionResolver;
import de.visterion.agora.research.IndicatorExpressionResolver.Resolved;
import de.visterion.agora.research.IndicatorExpressionResolver.SpecException;
import de.visterion.agora.research.IndicatorRegistry;
import de.visterion.agora.research.Ta4jBars;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
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

/** Generic indicator tool: computes any set of catalog indicators for a symbol in one
 *  call. Specs are composable ({name, params, of, label} or a plain name); series=N
 *  additionally returns the last N values. One OHLC fetch per call; per-spec problems
 *  degrade only that entry. Discover the catalog with list_indicators. */
@Component
public class GetIndicatorsTool implements AgoraTool {

    private static final int MAX_SPECS = 20;
    private static final int MAX_SERIES = 250;
    private static final int MAX_FETCH_DAYS = 1825;

    private final MarketDataService service;
    private final IndicatorRegistry registry;
    private final List<String> defaultIndicators;
    private final int fetchDays;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetIndicatorsTool(
            MarketDataService service,
            IndicatorRegistry registry,
            @Value("${agora.research.default-indicators:atr,chandelier_stop,ma_cross,52w_range}")
            List<String> defaultIndicators,
            @Value("${agora.research.fetch-days:260}") int fetchDays) {
        this.service = service;
        this.registry = registry;
        this.defaultIndicators = List.copyOf(defaultIndicators);
        this.fetchDays = fetchDays;
    }

    @Override
    public String name() { return "get_indicators"; }

    @Override
    public String description() {
        return "Computes technical indicators for a symbol in one call. 'indicators' is a list "
             + "of specs: a catalog name (string) or {name, params, of, label}; 'of' composes "
             + "indicators (e.g. sma of rsi) or picks a price source. Optional series=N returns "
             + "the last N values. Defaults to " + String.join(",", defaultIndicators)
             + ". Discover the catalog with list_indicators.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol (e.g. AAPL)");
        props.putObject("indicators").put("type", "array")
             .put("description", "Indicator specs: a catalog name (string) or an object "
                     + "{name, params?, of?, label?}. 'of' is a nested spec or a price source "
                     + "(close|open|high|low|volume|typical; default close). Max " + MAX_SPECS
                     + " specs. Default: " + String.join(",", defaultIndicators));
        props.putObject("series").put("type", "integer")
             .put("description", "Also return the last N values per indicator (0.." + MAX_SERIES
                     + ", default 0)");
        props.putObject("fetchDays").put("type", "integer")
             .put("description", "History window in days (default " + fetchDays + ")");
        schema.putArray("required").add("symbol");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("symbol")) return ToolResult.unavailable("no symbol provided");
        String symbol = args.get("symbol").asString();

        int seriesN;
        try {
            seriesN = intArg(args, "series", 0);
        } catch (IllegalArgumentException e) {
            return ToolResult.unavailable("invalid series");
        }
        if (seriesN < 0 || seriesN > MAX_SERIES) {
            return ToolResult.unavailable("series must be 0.." + MAX_SERIES);
        }
        int days;
        try {
            days = intArg(args, "fetchDays", fetchDays);
        } catch (IllegalArgumentException e) {
            return ToolResult.unavailable("invalid fetchDays");
        }
        if (days <= 0) return ToolResult.unavailable("invalid fetchDays");
        // M-X4: silently clamp — mirrors b5b32a7's days<=1825 clamp on the other tools.
        days = Math.clamp(days, 1, MAX_FETCH_DAYS);

        JsonNode indicatorsNode;
        if (args.has("indicators") && !args.get("indicators").isNull()) {
            indicatorsNode = args.get("indicators");
        } else {
            ArrayNode defaults = mapper.createArrayNode();
            defaultIndicators.forEach(defaults::add);
            indicatorsNode = defaults;
        }
        if (!indicatorsNode.isArray()) return ToolResult.unavailable("indicators must be an array");
        if (indicatorsNode.size() == 0) return ToolResult.unavailable("indicators must not be empty");
        if (indicatorsNode.size() > MAX_SPECS) {
            return ToolResult.unavailable("too many indicator specs (max " + MAX_SPECS + ")");
        }

        List<OhlcBar> bars;
        try {
            bars = service.ohlc(symbol, days);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
        if (bars.isEmpty()) return ToolResult.unavailable("no data for " + symbol);

        BarSeries series = Ta4jBars.toSeries(bars);
        IndicatorExpressionResolver resolver = new IndicatorExpressionResolver(registry);

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("currentClose", bars.getLast().close());
        out.put("asOf", bars.getLast().date().toString());
        ArrayNode values = out.putArray("values");
        Set<String> usedLabels = new HashSet<>();
        boolean anySpecAvailable = false;

        for (JsonNode specNode : indicatorsNode) {
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
        return ToolResult.ok(out);
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

    private static int intArg(JsonNode args, String field, int fallback) {
        if (!args.has(field) || args.get(field).isNull()) return fallback;
        JsonNode node = args.get(field);
        if (!node.isNumber() || !node.canConvertToExactIntegral()) {
            throw new IllegalArgumentException("field '" + field + "' must be an integer");
        }
        return node.asInt();
    }
}
