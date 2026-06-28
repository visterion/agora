package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.research.IndicatorService;
import de.visterion.agora.research.Indicators;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Tool: {@code get_52w_range} — 52-week high/low for a symbol.
 *
 * <p>Args: {@code symbol} (required). No optional overrides.</p>
 */
@Component
public class GetFiftyTwoWeekRangeTool implements AgoraTool {

    private final MarketDataService service;
    private final IndicatorService indicators;
    private final int fetchDays;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Spring-wired constructor. */
    public GetFiftyTwoWeekRangeTool(
            MarketDataService service,
            IndicatorService indicators,
            @Value("${agora.research.fetch-days:260}") int fetchDays) {
        this.service = service;
        this.indicators = indicators;
        this.fetchDays = fetchDays;
    }

    @Override
    public String name() { return "get_52w_range"; }

    @Override
    public String description() {
        IndicatorService.Params dp = indicators.defaultParams();
        return "52-week high and low for a symbol. Requires at least " + dp.minBarsFor52w() +
               " trading days of history (available=false when history is insufficient).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol (e.g. AAPL)");
        schema.putArray("required").add("symbol");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("symbol")) {
            return ToolResult.unavailable("no symbol provided");
        }
        String symbol = args.get("symbol").asString();

        IndicatorService.Params params = indicators.defaultParams();

        List<OhlcBar> bars;
        try {
            bars = service.ohlc(symbol, fetchDays);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }

        Indicators ind = indicators.compute(bars, params);
        if (!ind.window52wAvailable()) {
            return ToolResult.unavailable("insufficient history for 52w_range");
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("high", ind.high52w());
        out.put("low", ind.low52w());
        out.put("available", true);
        return ToolResult.ok(out);
    }
}
