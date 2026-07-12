package de.visterion.agora.tools;

import de.visterion.agora.fetch.reference.change.IndexChange;
import de.visterion.agora.fetch.reference.change.IndexChangeService;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetIndexConstituentChangesTool implements AgoraTool {

    private static final int DEFAULT_LOOKBACK_DAYS = 30;

    private final IndexChangeService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetIndexConstituentChangesTool(IndexChangeService service) { this.service = service; }

    public String name() { return "get_index_constituent_changes"; }

    public String description() {
        return "Pending and recent constituent changes for a stock index "
                + "(add/remove, announcement + effective dates).";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("index").put("type", "string").put("description", "index name; default sp500");
        props.putObject("lookback_days").put("type", "integer")
                .put("description", "only changes announced within this many days; default 30");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String index = args == null ? "sp500" : args.path("index").asString("sp500");
        if (index.isBlank()) index = "sp500";
        int lookbackDays = args == null ? DEFAULT_LOOKBACK_DAYS
                : args.path("lookback_days").asInt(DEFAULT_LOOKBACK_DAYS);

        // The service never throws and degrades to an empty list, so there is no unavailable
        // path here (mirrors get_index_constituents' degrade-to-empty behaviour).
        List<IndexChange> changes = service.changes(index, lookbackDays);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode arr = out.putArray("changes");
        for (IndexChange c : changes) {
            ObjectNode o = arr.addObject();
            o.put("symbol", c.symbol());
            o.put("action", c.action());
            o.put("index", c.index());
            o.put("announcementDate", c.announcementDate() == null ? null : c.announcementDate().toString());
            o.put("effectiveDate", c.effectiveDate() == null ? null : c.effectiveDate().toString());
            o.put("source", c.source());
        }
        return ToolResult.ok(out);
    }
}
