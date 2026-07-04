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
import org.ta4j.core.indicators.WilliamsRIndicator;
import org.ta4j.core.num.Num;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetWilliamsRTool implements AgoraTool {

    private final MarketDataService service;
    private final int defaultPeriod;
    private final int fetchDays;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GetWilliamsRTool(MarketDataService service, ResearchDefaults d) {
        this(service, d.williamsPeriod(), d.fetchDays());
    }

    // Test ctor
    GetWilliamsRTool(MarketDataService service, int defaultPeriod, int fetchDays) {
        this.service = service;
        this.defaultPeriod = defaultPeriod;
        this.fetchDays = fetchDays;
    }

    public String name() { return "get_williams_r"; }
    public String description() { return "Williams %R for a symbol. Default period " + defaultPeriod + "."; }

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

        if (bars.size() < period) return ToolResult.unavailable("insufficient history for williams_r");
        BarSeries series = Ta4jBars.toSeries(bars);
        WilliamsRIndicator wr = new WilliamsRIndicator(series, period);
        Num v = wr.getValue(series.getEndIndex());
        if (v.isNaN()) return ToolResult.unavailable("insufficient history for williams_r");

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("williamsR", Ta4jBars.toBd(v, 4));
        out.put("available", true);
        return ToolResult.ok(out);
    }
}
