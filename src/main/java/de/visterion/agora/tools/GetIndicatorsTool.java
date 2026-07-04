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
 * Tool: {@code get_indicators} — the full bundled technical-indicator set for a
 * symbol in one call.
 *
 * <p>Fetches OHLC history once and runs {@link IndicatorService#compute} to emit the
 * whole {@link Indicators} record (ATR, Chandelier stop, MA cross, 52-week range,
 * plus current close). Reuses the exact Slice-3/6 compute for value parity with the
 * per-indicator tools.</p>
 *
 * <p>Args: {@code symbol} (required); optional {@code period}, {@code multiple},
 * {@code maFast}, {@code maSlow}, {@code minBars52w}.</p>
 */
@Component
public class GetIndicatorsTool implements AgoraTool {

    private final MarketDataService service;
    private final IndicatorService indicators;
    private final int fetchDays;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Spring-wired constructor. */
    public GetIndicatorsTool(
            MarketDataService service,
            IndicatorService indicators,
            @Value("${agora.research.fetch-days:260}") int fetchDays) {
        this.service = service;
        this.indicators = indicators;
        this.fetchDays = fetchDays;
    }

    @Override
    public String name() { return "get_indicators"; }

    @Override
    public String description() {
        return "Bundled technical indicators (ATR, Chandelier stop, MA cross, 52-week range) " +
               "for a symbol in one call. Optional: period, multiple, maFast, maSlow, minBars52w.";
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
             .put("description", "ATR multiplier for the Chandelier stop (default: " + dp.atrMultiple() + ")");
        props.putObject("maFast").put("type", "integer")
             .put("description", "Fast moving-average period (default: " + dp.maFast() + ")");
        props.putObject("maSlow").put("type", "integer")
             .put("description", "Slow moving-average period (default: " + dp.maSlow() + ")");
        props.putObject("minBars52w").put("type", "integer")
             .put("description", "Minimum bars for the 52-week window flag (default: " + dp.minBarsFor52w() + ")");
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
        int maFast = (args.has("maFast") && !args.get("maFast").isNull())
                ? args.get("maFast").asInt() : dp.maFast();
        int maSlow = (args.has("maSlow") && !args.get("maSlow").isNull())
                ? args.get("maSlow").asInt() : dp.maSlow();
        int minBars52w = (args.has("minBars52w") && !args.get("minBars52w").isNull())
                ? args.get("minBars52w").asInt() : dp.minBarsFor52w();

        IndicatorService.Params params;
        try {
            BigDecimal multiple = (args.has("multiple") && !args.get("multiple").isNull())
                    ? new BigDecimal(args.get("multiple").asString()) : dp.atrMultiple();
            params = new IndicatorService.Params(period, multiple, maFast, maSlow, minBars52w);
        } catch (IllegalArgumentException e) {
            return ToolResult.unavailable("invalid parameter: " + e.getMessage());
        }

        List<OhlcBar> bars;
        try {
            bars = service.ohlc(symbol, fetchDays);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }

        Indicators ind = indicators.compute(bars, params);
        if (ind.currentClose() == null) {
            return ToolResult.unavailable("no data for " + symbol);
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        putDecimal(out, "currentClose", ind.currentClose());
        putDecimal(out, "atr", ind.atr());
        out.put("atrAvailable", ind.atrAvailable());
        putDecimal(out, "chandelierStop", ind.chandelierStop());
        out.put("chandelierBreached", ind.chandelierBreached());
        putDecimal(out, "maFast", ind.maFast());
        out.put("maFastAvailable", ind.maFastAvailable());
        putDecimal(out, "maSlow", ind.maSlow());
        out.put("maSlowAvailable", ind.maSlowAvailable());
        out.put("maCrossState", ind.maCrossState());
        putDecimal(out, "high52w", ind.high52w());
        putDecimal(out, "low52w", ind.low52w());
        out.put("window52wAvailable", ind.window52wAvailable());
        out.put("available", true);
        return ToolResult.ok(out);
    }

    private static void putDecimal(ObjectNode out, String name, BigDecimal value) {
        if (value != null) {
            out.put(name, value);
        } else {
            out.putNull(name);
        }
    }
}
