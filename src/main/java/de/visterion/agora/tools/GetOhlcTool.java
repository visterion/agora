package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetOhlcTool implements AgoraTool {

    private static final int DEFAULT_DAYS = 260;
    private static final int MAX_DAYS = 1825;
    private final MarketDataService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetOhlcTool(MarketDataService service) { this.service = service; }

    public String name() { return "get_ohlc"; }
    public String description() { return "Daily OHLCV history (oldest-first) for a symbol."; }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        props.putObject("days").put("type", "integer").put("description", "trading days of history (default 260, max " + MAX_DAYS + ")");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("symbol")) return ToolResult.unavailable("no symbol provided");
        String symbol = args.get("symbol").asString();
        int days = (args.has("days") && args.get("days").isIntegralNumber()) ? args.get("days").asInt() : DEFAULT_DAYS;
        days = Math.clamp(days, 1, MAX_DAYS);
        try {
            List<OhlcBar> bars = service.ohlc(symbol, days);
            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", symbol);
            ArrayNode arr = out.putArray("bars");
            for (OhlcBar b : bars) {
                ObjectNode o = arr.addObject();
                o.put("date", b.date().toString());
                o.put("open", b.open());
                o.put("high", b.high());
                o.put("low", b.low());
                o.put("close", b.close());
                o.put("volume", b.volume());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
