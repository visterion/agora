package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.finnhub.EstimatesService;
import de.visterion.agora.fetch.finnhub.Recommendation;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetAnalystEstimatesTool implements AgoraTool {

    private final EstimatesService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetAnalystEstimatesTool(EstimatesService service) { this.service = service; }

    public String name() { return "get_analyst_estimates"; }
    public String description() { return "Analyst recommendation trend for a symbol."; }

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
            List<Recommendation> recs = service.recommendations(symbol);
            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", symbol);
            ArrayNode arr = out.putArray("recommendations");
            for (Recommendation rec : recs) {
                ObjectNode o = arr.addObject();
                o.put("period", rec.period());
                o.put("strongBuy", rec.strongBuy());
                o.put("buy", rec.buy());
                o.put("hold", rec.hold());
                o.put("sell", rec.sell());
                o.put("strongSell", rec.strongSell());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
