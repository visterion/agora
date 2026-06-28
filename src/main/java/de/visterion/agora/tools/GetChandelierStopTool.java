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

import java.math.BigDecimal;
import java.util.List;

/**
 * Tool: {@code get_chandelier_stop} — Chandelier Exit stop level.
 *
 * <p>Args: {@code symbol} (required), {@code period} (optional), {@code multiple} (optional).</p>
 */
@Component
public class GetChandelierStopTool implements AgoraTool {

    private final MarketDataService service;
    private final IndicatorService indicators;
    private final int fetchDays;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Spring-wired constructor. */
    public GetChandelierStopTool(
            MarketDataService service,
            IndicatorService indicators,
            @Value("${agora.research.fetch-days:260}") int fetchDays) {
        this.service = service;
        this.indicators = indicators;
        this.fetchDays = fetchDays;
    }

    @Override
    public String name() { return "get_chandelier_stop"; }

    @Override
    public String description() {
        IndicatorService.Params dp = indicators.defaultParams();
        return "Chandelier Exit stop-loss level for a symbol. breached=true means price fell below the stop. " +
               "Optional: override ATR period (default: " + dp.atrPeriod() +
               ") and multiplier (default: " + dp.atrMultiple() + ").";
    }

    @Override
    public ObjectNode inputSchema() {
        IndicatorService.Params dp = indicators.defaultParams();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol (e.g. AAPL)");
        props.putObject("period").put("type", "integer")
             .put("description", "ATR look-back period in bars (default: " + dp.atrPeriod() + ")");
        props.putObject("multiple").put("type", "number")
             .put("description", "ATR multiplier for the stop distance (default: " + dp.atrMultiple() + ")");
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
        int period = (args.has("period") && !args.get("period").isNull())
                ? args.get("period").asInt() : dp.atrPeriod();
        BigDecimal multiple = (args.has("multiple") && !args.get("multiple").isNull())
                ? new BigDecimal(args.get("multiple").asString()) : dp.atrMultiple();

        IndicatorService.Params params = new IndicatorService.Params(
                period, multiple, dp.maFast(), dp.maSlow(), dp.minBarsFor52w());

        List<OhlcBar> bars;
        try {
            bars = service.ohlc(symbol, fetchDays);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }

        Indicators ind = indicators.compute(bars, params);
        if (!ind.atrAvailable()) {
            return ToolResult.unavailable("insufficient history for chandelier_stop");
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("chandelierStop", ind.chandelierStop());
        out.put("breached", ind.chandelierBreached());
        out.put("available", true);
        return ToolResult.ok(out);
    }
}
