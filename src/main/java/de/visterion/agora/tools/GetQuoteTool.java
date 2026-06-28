package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.Quote;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GetQuoteTool implements AgoraTool {

    private final MarketDataService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetQuoteTool(MarketDataService service) { this.service = service; }

    public String name() { return "get_quote"; }
    public String description() { return "Current price and day-change percent for one or more symbols."; }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("description", "Provide either 'symbols' (array of ticker strings) OR 'symbol' (single ticker string).");
        ObjectNode props = schema.putObject("properties");
        ObjectNode symbols = props.putObject("symbols");
        symbols.put("type", "array").put("description", "ticker symbols");
        symbols.putObject("items").put("type", "string");
        props.putObject("symbol").put("type", "string").put("description", "single ticker (alternative to symbols)");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        List<String> symbols = new ArrayList<>();
        if (args != null) {
            if (args.has("symbols") && args.get("symbols").isArray()) {
                for (JsonNode n : args.get("symbols")) symbols.add(n.asString());
            } else if (args.hasNonNull("symbol")) {
                symbols.add(args.get("symbol").asString());
            }
        }
        if (symbols.isEmpty()) return ToolResult.unavailable("no symbols provided");
        try {
            Map<String, Quote> resolved = service.quotes(symbols);
            if (resolved.isEmpty()) return ToolResult.unavailable("market data unavailable");
            ObjectNode out = mapper.createObjectNode();
            ArrayNode arr = out.putArray("quotes");
            for (Quote q : resolved.values()) {
                ObjectNode o = arr.addObject();
                o.put("symbol", q.symbol());
                o.put("price", q.price());
                o.put("dayChangePercent", q.dayChangePercent());
                o.put("currency", q.currency());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
