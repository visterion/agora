package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.news.NewsAggregator;
import de.visterion.agora.fetch.news.NewsAggregator.AggregatedNews;
import de.visterion.agora.fetch.news.NewsItem;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class GetCompanyNewsTool implements AgoraTool {

    private final NewsAggregator aggregator;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetCompanyNewsTool(NewsAggregator aggregator) { this.aggregator = aggregator; }

    public String name() { return "get_company_news"; }
    public String description() {
        return "Recent company news headlines for a symbol, merged from multiple sources. "
                + "Each item carries sourceType (news|social); partial provider failures are "
                + "reported in a top-level warnings array.";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        props.putObject("from").put("type", "string").put("description", "start date ISO (YYYY-MM-DD); default 7 days ago");
        props.putObject("to").put("type", "string").put("description", "end date ISO (YYYY-MM-DD); default today");
        ObjectNode types = props.putObject("sourceTypes");
        types.put("type", "array").put("description",
                "optional filter by media type: \"news\" (editorial/wire) and/or \"social\" "
                        + "(user-generated); case-insensitive; empty or omitted = all");
        types.putObject("items").put("type", "string");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? null : args.path("symbol").asString(null);
        if (symbol == null || symbol.isBlank()) return ToolResult.unavailable("no symbol provided");
        LocalDate from, to;
        try {
            String toRaw = args.path("to").asString(null);
            String fromRaw = args.path("from").asString(null);
            to = (toRaw == null || toRaw.isBlank()) ? LocalDate.now() : LocalDate.parse(toRaw);
            from = (fromRaw == null || fromRaw.isBlank()) ? to.minusDays(7) : LocalDate.parse(fromRaw);
        } catch (DateTimeParseException e) {
            return ToolResult.unavailable("invalid date");
        }
        Set<String> sourceTypes = new LinkedHashSet<>();
        JsonNode typesNode = args.path("sourceTypes");
        if (typesNode.isArray()) {
            for (JsonNode v : typesNode) {
                String s = v.asString(null);
                if (s != null && !s.isBlank()) sourceTypes.add(s);
            }
        }
        try {
            AggregatedNews agg = aggregator.aggregate(symbol, from, to, sourceTypes);
            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", symbol);
            ArrayNode arr = out.putArray("news");
            for (NewsItem n : agg.items()) {
                ObjectNode o = arr.addObject();
                o.put("headline", n.headline());
                o.put("summary", n.summary());
                o.put("source", n.source());
                o.put("sourceType", n.sourceType());
                if (n.datetime() == null) o.putNull("datetime");
                else o.put("datetime", n.datetime().toString());
                o.put("url", n.url());
            }
            if (!agg.warnings().isEmpty()) {
                ArrayNode w = out.putArray("warnings");
                agg.warnings().forEach(w::add);
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
