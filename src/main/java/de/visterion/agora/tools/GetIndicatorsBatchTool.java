package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.research.CompletedBarEvaluation;
import de.visterion.agora.research.ExchangeSessions;
import de.visterion.agora.research.IndicatorEvaluator;
import de.visterion.agora.research.IndicatorRegistry;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Many-symbol variant of {@code get_indicators}: one batched OHLC fetch for the whole symbol
 * list, then the identical per-symbol computation. Exists because screening a whole index one
 * {@code get_indicators} call at a time burns the provider's per-minute quota (measured
 * 2026-08-05: 49 of 645 Alpaca calls answered 429) — the batched path turns ~490 calls into ~15.
 *
 * <p>Every requested symbol appears in {@code results}, including the ones no provider served —
 * those carry {@code available:false} plus a reason, as does a symbol whose only bar belongs to a
 * session that is still running. A caller must be able to hold
 * {@code requested} against {@code returned} and see the gap, never guess at it.
 */
@Component
public class GetIndicatorsBatchTool implements AgoraTool {

    private static final Logger log = LoggerFactory.getLogger(GetIndicatorsBatchTool.class);

    /** Rejecting beyond this is deliberate: silently truncating a screening universe would make
     *  a half-screened index look like a fully screened one. 600 covers the S&P 500 plus room. */
    private static final int MAX_SYMBOLS = 600;

    private final MarketDataService service;
    private final IndicatorEvaluator evaluator;
    private final CompletedBarEvaluation completedBars;
    private final List<String> defaultIndicators;
    private final int fetchDays;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GetIndicatorsBatchTool(
            MarketDataService service,
            IndicatorEvaluator evaluator,
            ExchangeSessions sessions,
            @Value("${agora.research.default-indicators:atr,chandelier_stop,ma_cross,52w_range}")
            List<String> defaultIndicators,
            @Value("${agora.research.fetch-days:260}") int fetchDays) {
        this.service = service;
        this.evaluator = evaluator;
        this.completedBars = new CompletedBarEvaluation(evaluator, sessions);
        this.defaultIndicators = List.copyOf(defaultIndicators);
        this.fetchDays = fetchDays;
    }

    /** Test/back-compat constructor: builds the shared evaluator from a registry. */
    public GetIndicatorsBatchTool(MarketDataService service, IndicatorRegistry registry,
                                   ExchangeSessions sessions, List<String> defaultIndicators,
                                   int fetchDays) {
        this(service, new IndicatorEvaluator(registry), sessions, defaultIndicators, fetchDays);
    }

    @Override
    public String name() { return "get_indicators_batch"; }

    @Override
    public String description() {
        return "Computes the same technical indicators as get_indicators for many symbols in one "
             + "call, using a single batched history fetch instead of one per symbol — use this "
             + "for screening a universe. Same 'indicators'/'series'/'fetchDays' arguments and the "
             + "same per-symbol result object. Every requested symbol appears in 'results'; one "
             + "with no history has available=false and an error. Values are computed over "
             + "completed daily bars only; while a symbol's venue session is running, the "
             + "in-progress bar is exposed as currentClose/currentHigh/currentLow with "
             + "partialBar=true, and asOf names the last completed bar. Max " + MAX_SYMBOLS
             + " symbols (over that the call is rejected, not truncated).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode symbols = props.putObject("symbols");
        symbols.put("type", "array")
               .put("description", "Ticker symbols (e.g. AAPL). Max " + MAX_SYMBOLS
                       + "; a comma-separated string is also accepted.");
        symbols.putObject("items").put("type", "string");
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
        schema.putArray("required").add("symbols");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        List<String> symbols = symbolsOf(args);
        if (symbols.isEmpty()) return ToolResult.unavailable("no symbols provided");
        if (symbols.size() > MAX_SYMBOLS) {
            return ToolResult.unavailable("too many symbols (max " + MAX_SYMBOLS + ", got "
                    + symbols.size() + ") — split the request; the list is never truncated");
        }

        IndicatorArgs parsed;
        try {
            parsed = IndicatorArgs.parse(args, defaultIndicators, fetchDays, mapper);
        } catch (IllegalArgumentException e) {
            return ToolResult.unavailable(e.getMessage());
        }

        Map<String, List<OhlcBar>> barsBySymbol;
        try {
            barsBySymbol = service.ohlcBatch(symbols, parsed.days());
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }

        ObjectNode out = mapper.createObjectNode();
        ArrayNode results = out.putArray("results");
        int returned = 0;
        int droppedInProgress = 0;
        for (String symbol : symbols) {
            List<OhlcBar> raw = barsBySymbol.get(symbol);
            if (raw == null || raw.isEmpty()) {
                results.add(evaluator.unavailable(symbol, "no data for " + symbol));
                continue;
            }
            // Identical normalisation and guard as get_indicators — the two tools must not be
            // able to disagree about which bar is the newest completed one.
            ObjectNode entry = completedBars.evaluate(symbol, raw, parsed.specs(), parsed.seriesN());
            results.add(entry);
            if (entry.path("available").asBoolean(false)) returned++;
            if (entry.path("partialBar").asBoolean(false)) droppedInProgress++;
        }
        // One summary line, not one per symbol: a single call can carry 600 symbols, and the
        // per-firing detail already lives in ExchangeSessions at DEBUG.
        if (droppedInProgress > 0) {
            log.info("session guard: dropped in-progress bars for {} of {} symbols",
                    droppedInProgress, symbols.size());
        }
        out.put("requested", symbols.size());
        // returned = symbols that produced at least one indicator value; the difference to
        // requested is what the caller must account for (no history, a provider gap, or only an
        // in-progress bar).
        out.put("returned", returned);
        // Same rule as the single-symbol tool, one level up: false only when nothing at all
        // could be computed.
        out.put("available", returned > 0);
        return ToolResult.ok(out);
    }

    /** Accepts symbols as an array or, like get_quote, as a comma-separated string. Blanks and
     *  duplicates are dropped so 'requested' counts what was really asked for. Dedup is
     *  case-insensitive, first spelling wins: tickers are case-insensitive upstream, so
     *  ["aapl","AAPL"] is one symbol — deduping case-sensitively would send both and report one
     *  of them back as "no data". */
    private static List<String> symbolsOf(JsonNode args) {
        Map<String, String> byUpper = new LinkedHashMap<>();
        if (args != null && args.has("symbols")) {
            JsonNode node = args.get("symbols");
            if (node.isArray()) {
                for (JsonNode n : node) addIfPresent(byUpper, n.asString(""));
            } else if (node.isString()) {
                for (String part : node.asString("").split(",")) addIfPresent(byUpper, part);
            }
        }
        return new ArrayList<>(byUpper.values());
    }

    private static void addIfPresent(Map<String, String> byUpper, String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.isEmpty()) byUpper.putIfAbsent(s.toUpperCase(Locale.ROOT), s);
    }
}
