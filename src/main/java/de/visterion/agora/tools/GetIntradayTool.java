package de.visterion.agora.tools;

import de.visterion.agora.data.IntradayBar;
import de.visterion.agora.data.IntradayService;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetIntradayTool implements AgoraTool {

    private final IntradayService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetIntradayTool(IntradayService service) { this.service = service; }

    public String name() { return "get_intraday"; }
    public String description() { return "Intraday OHLCV candles for a symbol at a given interval/range."; }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        props.putObject("interval").put("type", "string").put("description", "candle interval, e.g. 1m/5m/15m/1h");
        props.putObject("range").put("type", "string").put("description", "lookback range, e.g. 1d/5d");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? null : args.path("symbol").asString(null);
        if (symbol == null || symbol.isBlank()) return ToolResult.unavailable("no symbol provided");
        String interval = args.path("interval").asString(null);
        String range = args.path("range").asString(null);
        try {
            List<IntradayBar> bars = service.intraday(symbol, interval, range);
            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", symbol);
            ArrayNode arr = out.putArray("bars");
            for (IntradayBar b : bars) {
                ObjectNode o = arr.addObject();
                o.put("time", b.time().toString());
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
