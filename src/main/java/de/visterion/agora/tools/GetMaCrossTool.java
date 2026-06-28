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
 * Tool: {@code get_ma_cross} — moving-average cross state.
 *
 * <p>Args: {@code symbol} (required), {@code fast} (optional), {@code slow} (optional).</p>
 */
@Component
public class GetMaCrossTool implements AgoraTool {

    private final MarketDataService service;
    private final IndicatorService indicators;
    private final int fetchDays;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Spring-wired constructor. */
    public GetMaCrossTool(
            MarketDataService service,
            IndicatorService indicators,
            @Value("${agora.research.fetch-days:260}") int fetchDays) {
        this.service = service;
        this.indicators = indicators;
        this.fetchDays = fetchDays;
    }

    @Override
    public String name() { return "get_ma_cross"; }

    @Override
    public String description() {
        IndicatorService.Params dp = indicators.defaultParams();
        return "Moving-average cross state for a symbol: BULLISH (fast MA > slow MA), " +
               "DEATH_CROSS (fast < slow), or NEUTRAL (insufficient history). " +
               "Optional: override fast (default: " + dp.maFast() +
               ") and slow (default: " + dp.maSlow() + ") periods.";
    }

    @Override
    public ObjectNode inputSchema() {
        IndicatorService.Params dp = indicators.defaultParams();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol (e.g. AAPL)");
        props.putObject("fast").put("type", "integer")
             .put("description", "Fast MA period in bars (default: " + dp.maFast() + ")");
        props.putObject("slow").put("type", "integer")
             .put("description", "Slow MA period in bars (default: " + dp.maSlow() + ")");
        schema.putArray("required").add("symbol");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("symbol")) {
            return ToolResult.unavailable("no symbol provided");
        }
        String symbol = args.get("symbol").asString();

        IndicatorService.Params dp = indicators.defaultParams();
        int fast = (args.has("fast") && !args.get("fast").isNull())
                ? args.get("fast").asInt() : dp.maFast();
        int slow = (args.has("slow") && !args.get("slow").isNull())
                ? args.get("slow").asInt() : dp.maSlow();

        IndicatorService.Params params = new IndicatorService.Params(
                dp.atrPeriod(), dp.atrMultiple(), fast, slow, dp.minBarsFor52w());

        List<OhlcBar> bars;
        try {
            bars = service.ohlc(symbol, fetchDays);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }

        Indicators ind = indicators.compute(bars, params);
        if (!ind.maFastAvailable() || !ind.maSlowAvailable()) {
            return ToolResult.unavailable("insufficient history for ma_cross");
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("maFast", ind.maFast());
        out.put("maSlow", ind.maSlow());
        out.put("crossState", ind.maCrossState());
        out.put("available", true);
        return ToolResult.ok(out);
    }
}
