package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.news.NewsItem;
import de.visterion.agora.fetch.news.FinnhubNewsProvider;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
public class GetCompanyNewsTool implements AgoraTool {

    private final FinnhubNewsProvider service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetCompanyNewsTool(FinnhubNewsProvider service) { this.service = service; }

    public String name() { return "get_company_news"; }
    public String description() { return "Recent company news headlines for a symbol."; }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        props.putObject("from").put("type", "string").put("description", "start date ISO (YYYY-MM-DD); default 7 days ago");
        props.putObject("to").put("type", "string").put("description", "end date ISO (YYYY-MM-DD); default today");
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
        try {
            List<NewsItem> news = service.companyNews(symbol, from, to);
            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", symbol);
            ArrayNode arr = out.putArray("news");
            for (NewsItem n : news) {
                ObjectNode o = arr.addObject();
                o.put("headline", n.headline());
                o.put("summary", n.summary());
                o.put("source", n.source());
                o.put("datetime", n.datetime().toString());
                o.put("url", n.url());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
