package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.search.InstrumentSearchService;
import de.visterion.agora.fetch.search.SearchHit;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolParams;
import de.visterion.agora.tool.ToolParams.InvalidArgumentException;
import de.visterion.agora.tool.ToolResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/** Find instruments by ticker or company name. */
@Component
public class SearchInstrumentsTool implements AgoraTool {

    /** Seam so tests can drive the tool without the service graph. */
    interface Search {
        List<SearchHit> search(String query, int limit);
    }

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 25;

    private final Search search;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public SearchInstrumentsTool(InstrumentSearchService service) {
        this(service::search);
    }

    SearchInstrumentsTool(Search search) {
        this.search = search;
    }

    public String name() { return "search_instruments"; }

    public String description() {
        return "Resolve a ticker or company name to tradable instrument symbols. Use this when "
             + "you have a company name but not its symbol, or when a symbol did not resolve. "
             + "Returns symbol, company name, exchange and instrument type, best match first, "
             + "across all venues (a company may appear as a US ADR and a home-market listing, "
             + "e.g. NOK on NYSE and NOKIA.HE in Helsinki). No prices — call get_quote for those.";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("query").put("type", "string")
                .put("description", "ticker fragment or company name, e.g. \"nokia\"");
        props.putObject("limit").put("type", "integer")
                .put("description", "max results, default 10, capped at 25");
        schema.putArray("required").add("query");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String query = args == null ? "" : args.path("query").asString("").trim();
        if (query.isEmpty()) return ToolResult.unavailable("no query provided");

        int limit;
        try {
            Integer limitArg = ToolParams.optionalInt(args, "limit");
            limit = limitArg == null ? DEFAULT_LIMIT : limitArg;
        } catch (InvalidArgumentException e) {
            return ToolResult.unavailable(e.getMessage());
        }
        limit = Math.clamp(limit, 1, MAX_LIMIT);

        try {
            List<SearchHit> hits = search.search(query, limit);
            ObjectNode out = mapper.createObjectNode();
            ArrayNode arr = out.putArray("results");
            for (SearchHit h : hits) {
                ObjectNode o = arr.addObject();
                o.put("symbol", h.symbol());
                o.put("name", h.name());
                o.put("exchange", h.exchange());
                o.put("type", h.type());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
