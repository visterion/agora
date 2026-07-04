package de.visterion.agora.tools;

import de.visterion.agora.data.FxRate;
import de.visterion.agora.data.FxService;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GetFxRateTool implements AgoraTool {

    private final FxService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetFxRateTool(FxService service) { this.service = service; }

    public String name() { return "get_fx_rate"; }
    public String description() { return "Current FX conversion rate: 1 unit of 'from' in 'to' currency."; }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("from").put("type", "string").put("description", "source currency ISO code");
        props.putObject("to").put("type", "string").put("description", "target currency ISO code");
        schema.putArray("required").add("from").add("to");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String from = args == null ? null : args.path("from").asString(null);
        String to = args == null ? null : args.path("to").asString(null);
        if (from == null || from.isBlank() || to == null || to.isBlank())
            return ToolResult.unavailable("from and to required");
        try {
            FxRate fx = service.rate(from, to);
            ObjectNode out = mapper.createObjectNode();
            out.put("from", fx.from());
            out.put("to", fx.to());
            out.put("rate", fx.rate());
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
