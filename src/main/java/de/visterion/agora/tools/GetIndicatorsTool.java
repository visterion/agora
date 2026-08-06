package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.research.IndicatorEvaluator;
import de.visterion.agora.research.IndicatorRegistry;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/** Generic indicator tool: computes any set of catalog indicators for a symbol in one
 *  call. Specs are composable ({name, params, of, label} or a plain name); series=N
 *  additionally returns the last N values. One OHLC fetch per call; per-spec problems
 *  degrade only that entry. Discover the catalog with list_indicators. For many symbols
 *  at once use get_indicators_batch, which computes the identical values per symbol. */
@Component
public class GetIndicatorsTool implements AgoraTool {

    private final MarketDataService service;
    private final IndicatorEvaluator evaluator;
    private final List<String> defaultIndicators;
    private final int fetchDays;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GetIndicatorsTool(
            MarketDataService service,
            IndicatorEvaluator evaluator,
            @Value("${agora.research.default-indicators:atr,chandelier_stop,ma_cross,52w_range}")
            List<String> defaultIndicators,
            @Value("${agora.research.fetch-days:260}") int fetchDays) {
        this.service = service;
        this.evaluator = evaluator;
        this.defaultIndicators = List.copyOf(defaultIndicators);
        this.fetchDays = fetchDays;
    }

    /** Test/back-compat constructor: builds the shared evaluator from a registry. */
    public GetIndicatorsTool(MarketDataService service, IndicatorRegistry registry,
                             List<String> defaultIndicators, int fetchDays) {
        this(service, new IndicatorEvaluator(registry), defaultIndicators, fetchDays);
    }

    @Override
    public String name() { return "get_indicators"; }

    @Override
    public String description() {
        return "Computes technical indicators for a symbol in one call. 'indicators' is a list "
             + "of specs: a catalog name (string) or {name, params, of, label}; 'of' composes "
             + "indicators (e.g. sma of rsi) or picks a price source. Optional series=N returns "
             + "the last N values. Defaults to " + String.join(",", defaultIndicators)
             + ". Discover the catalog with list_indicators. For many symbols in one call use "
             + "get_indicators_batch.";
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
                     + "(close|open|high|low|volume|typical; default close). Max " + IndicatorArgs.MAX_SPECS
                     + " specs. Default: " + String.join(",", defaultIndicators));
        props.putObject("series").put("type", "integer")
             .put("description", "Also return the last N values per indicator (0.." + IndicatorArgs.MAX_SERIES
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

        IndicatorArgs parsed;
        try {
            parsed = IndicatorArgs.parse(args, defaultIndicators, fetchDays, mapper);
        } catch (IllegalArgumentException e) {
            return ToolResult.unavailable(e.getMessage());
        }

        List<OhlcBar> bars;
        try {
            bars = service.ohlc(symbol, parsed.days());
        } catch (MarketDataException e) {
            // NOT_FOUND = this one symbol has no history anywhere in the chain. get_indicators_batch
            // has always answered that with an available:false entry rather than an error; the
            // single-symbol tool now says the identical thing in the identical shape.
            if (e.kind() == MarketDataException.Kind.NOT_FOUND) {
                return ToolResult.ok(evaluator.unavailable(symbol, e.getMessage()));
            }
            return ToolResult.unavailable(e.getMessage());
        }
        // Same statement, reached without an exception: bars came back empty for this symbol.
        if (bars.isEmpty()) return ToolResult.ok(evaluator.unavailable(symbol, "no data for " + symbol));

        return ToolResult.ok(evaluator.evaluate(symbol, bars, parsed.specs(), parsed.seriesN()));
    }
}
