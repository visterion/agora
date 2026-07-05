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

        int seriesN = intArg(args, "series", 0);
        if (seriesN < 0 || seriesN > MAX_SERIES) {
            return ToolResult.unavailable("series must be 0.." + MAX_SERIES);
        }
        int days = intArg(args, "fetchDays", fetchDays);
        if (days <= 0) return ToolResult.unavailable("invalid fetchDays");

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

        for (JsonNode specNode : indicatorsNode) {
            ObjectNode entry = values.addObject();
            try {
                Resolved r = resolver.resolve(specNode, series);
                if (!usedLabels.add(r.label())) {
                    throw new SpecException("duplicate label '" + r.label()
                            + "' — set an explicit label");
                }
                entry.put("label", r.label());
                writeValues(entry, r, series, seriesN);
            } catch (SpecException e) {
                entry.put("available", false);
                entry.put("error", e.getMessage());
            }
        }
        out.put("available", true);
        return ToolResult.ok(out);
    }

    private static void writeValues(ObjectNode entry, Resolved r, BarSeries series, int seriesN) {
        if (series.getBarCount() < r.minBars()) {
            markInsufficient(entry, r);
            return;
        }
        int end = series.getEndIndex();
        if (r.def().singleOutput()) {
            Indicator<Num> ind = r.outputs().values().iterator().next();
            Num v = ind.getValue(end);
            if (v.isNaN()) {
                markInsufficient(entry, r);
                return;
            }
            entry.put("value", Ta4jBars.toBd(v, 4));
            if (seriesN > 0) writeSeries(entry.putArray("series"), ind, series, seriesN);
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
                markInsufficient(entry, r);
                return;
            }
            if (seriesN > 0) {
                ObjectNode seriesObj = entry.putObject("series");
                for (Map.Entry<String, Indicator<Num>> o : r.outputs().entrySet()) {
                    writeSeries(seriesObj.putArray(o.getKey()), o.getValue(), series, seriesN);
                }
            }
        }
        entry.put("available", true);
    }

    private static void markInsufficient(ObjectNode entry, Resolved r) {
        entry.put("available", false);
        entry.put("error", "insufficient history for " + r.def().name());
    }

    private static void writeSeries(ArrayNode arr, Indicator<Num> ind, BarSeries series, int n) {
        int end = series.getEndIndex();
        int from = Math.max(series.getBeginIndex(), end - n + 1);
        for (int i = from; i <= end; i++) {
            Num v = ind.getValue(i);
            if (v.isNaN()) arr.addNull();
            else arr.add(Ta4jBars.toBd(v, 4));
        }
    }

    private static int intArg(JsonNode args, String field, int fallback) {
        return (args.has(field) && !args.get(field).isNull()) ? args.get(field).asInt() : fallback;
    }
}
