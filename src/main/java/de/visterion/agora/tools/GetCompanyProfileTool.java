package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.finnhub.Profile;
import de.visterion.agora.fetch.finnhub.ProfileService;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GetCompanyProfileTool implements AgoraTool {

    private final ProfileService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetCompanyProfileTool(ProfileService service) { this.service = service; }

    public String name() { return "get_company_profile"; }
    public String description() { return "Company profile (name, industry, exchange, market cap) for a symbol."; }

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
            Profile p = service.profile(symbol);
            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", p.symbol());
            out.set("profile", p.profile().deepCopy());
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
