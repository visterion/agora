package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.reference.Constituent;
import de.visterion.agora.fetch.reference.WikipediaService;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetIndexConstituentsTool implements AgoraTool {

    private final WikipediaService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetIndexConstituentsTool(WikipediaService service) { this.service = service; }

    public String name() { return "get_index_constituents"; }
    public String description() { return "Constituents of a stock index (default sp500)."; }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("index").put("type", "string").put("description", "index name; default sp500");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String index = args == null ? "sp500" : args.path("index").asString("sp500");
        try {
            List<Constituent> constituents = service.constituents(index);
            ObjectNode out = mapper.createObjectNode();
            out.put("index", index);
            ArrayNode arr = out.putArray("constituents");
            for (Constituent c : constituents) {
                ObjectNode o = arr.addObject();
                o.put("symbol", c.symbol());
                o.put("name", c.name());
                o.put("sector", c.sector());
                o.put("dateAdded", c.dateAdded() == null ? null : c.dateAdded().toString());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
