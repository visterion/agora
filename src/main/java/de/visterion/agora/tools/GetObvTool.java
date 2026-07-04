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
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.num.Num;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetObvTool implements AgoraTool {

    private final MarketDataService service;
    private final int fetchDays;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GetObvTool(MarketDataService service, ResearchDefaults d) {
        this(service, d.fetchDays());
    }

    // Test ctor
    GetObvTool(MarketDataService service, int fetchDays) {
        this.service = service;
        this.fetchDays = fetchDays;
    }

    public String name() { return "get_obv"; }
    public String description() { return "On-Balance Volume (OBV) for a symbol."; }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("symbol")) return ToolResult.unavailable("no symbol provided");
        String symbol = args.get("symbol").asString();

        List<OhlcBar> bars;
        try { bars = service.ohlc(symbol, fetchDays); }
        catch (MarketDataException e) { return ToolResult.unavailable(e.getMessage()); }

        if (bars.size() < 2) return ToolResult.unavailable("insufficient history for obv");
        BarSeries series = Ta4jBars.toSeries(bars);
        OnBalanceVolumeIndicator obv = new OnBalanceVolumeIndicator(series);
        Num v = obv.getValue(series.getEndIndex());
        if (v.isNaN()) return ToolResult.unavailable("insufficient history for obv");

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("obv", v.bigDecimalValue());
        out.put("available", true);
        return ToolResult.ok(out);
    }
}
