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
import java.util.Set;

@Component
public class GetIntradayTool implements AgoraTool {

    // Yahoo Finance's supported chart intervals/ranges (v8/finance/chart). Validating here means
    // a typo comes back as an explicit invalid-argument error instead of a misleading Yahoo
    // error surfaced as "market data unavailable" (fake outage).
    private static final Set<String> VALID_INTERVALS = Set.of(
            "1m", "2m", "5m", "15m", "30m", "60m", "90m", "1h", "1d", "5d", "1wk", "1mo", "3mo");
    private static final Set<String> VALID_RANGES = Set.of(
            "1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y", "10y", "ytd", "max");

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
        if (interval != null && !interval.isBlank() && !VALID_INTERVALS.contains(interval))
            return ToolResult.unavailable("invalid interval: " + interval);
        if (range != null && !range.isBlank() && !VALID_RANGES.contains(range))
            return ToolResult.unavailable("invalid range: " + range);
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
