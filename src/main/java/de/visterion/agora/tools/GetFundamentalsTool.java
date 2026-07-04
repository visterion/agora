package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.finnhub.Fundamentals;
import de.visterion.agora.fetch.finnhub.FundamentalsService;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GetFundamentalsTool implements AgoraTool {

    private final FundamentalsService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetFundamentalsTool(FundamentalsService service) { this.service = service; }

    public String name() { return "get_fundamentals"; }
    public String description() { return "Fundamental metrics for a symbol."; }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? null : args.path("symbol").asString(null);
        if (symbol == null || symbol.isBlank()) return ToolResult.unavailable("no symbol provided");
        try {
            Fundamentals f = service.fundamentals(symbol);
            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", f.symbol());
            out.set("metrics", f.metrics());
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
