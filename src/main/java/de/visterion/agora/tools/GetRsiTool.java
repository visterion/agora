package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.research.ResearchDefaults;
import de.visterion.agora.research.Ta4jBars;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetRsiTool implements AgoraTool {

    private final MarketDataService service;
    private final int defaultPeriod;
    private final int fetchDays;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GetRsiTool(MarketDataService service, ResearchDefaults d) {
        this(service, d.rsiPeriod(), d.fetchDays());
    }

    // Test ctor
    GetRsiTool(MarketDataService service, int defaultPeriod, int fetchDays) {
        this.service = service;
        this.defaultPeriod = defaultPeriod;
        this.fetchDays = fetchDays;
    }

    public String name() { return "get_rsi"; }
    public String description() { return "Relative Strength Index (RSI) for a symbol. Default period " + defaultPeriod + "."; }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol");
        props.putObject("period").put("type", "integer").put("description", "look-back period (default " + defaultPeriod + ")");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("symbol")) return ToolResult.unavailable("no symbol provided");
        String symbol = args.get("symbol").asString();
        int period = (args.has("period") && !args.get("period").isNull()) ? args.get("period").asInt() : defaultPeriod;
        if (period <= 0) return ToolResult.unavailable("invalid period");

        List<OhlcBar> bars;
        try { bars = service.ohlc(symbol, fetchDays); }
        catch (MarketDataException e) { return ToolResult.unavailable(e.getMessage()); }

        if (bars.size() < period + 1) return ToolResult.unavailable("insufficient history for rsi");
        BarSeries series = Ta4jBars.toSeries(bars);
        RSIIndicator rsi = new RSIIndicator(new ClosePriceIndicator(series), period);
        Num v = rsi.getValue(series.getEndIndex());
        if (v.isNaN()) return ToolResult.unavailable("insufficient history for rsi");

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("rsi", Ta4jBars.toBd(v, 4));
        out.put("available", true);
        return ToolResult.ok(out);
    }
}
