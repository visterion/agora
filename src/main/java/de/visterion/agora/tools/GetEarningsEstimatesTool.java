package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.finnhub.EarningsEstimate;
import de.visterion.agora.fetch.finnhub.EarningsEstimatesService;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetEarningsEstimatesTool implements AgoraTool {

    private final EarningsEstimatesService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetEarningsEstimatesTool(EarningsEstimatesService service) { this.service = service; }

    public String name() { return "get_earnings_estimates"; }
    public String description() {
        return "Reported EPS vs. estimate per period with the raw surprise delta (actual - estimate). "
             + "Raw passthrough — no standardization or scoring.";
    }

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
            List<EarningsEstimate> rows = service.earnings(symbol);
            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", symbol);
            ArrayNode arr = out.putArray("earnings");
            for (EarningsEstimate e : rows) {
                ObjectNode o = arr.addObject();
                o.put("period", e.period());
                o.put("actual", e.actual());
                o.put("estimate", e.estimate());
                o.put("surprise", e.surprise());
                o.put("surprisePercent", e.surprisePercent());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
