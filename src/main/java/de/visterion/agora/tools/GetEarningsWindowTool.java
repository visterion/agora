package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.earnings.EarningsEvent;
import de.visterion.agora.fetch.earnings.EarningsService;
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
public class GetEarningsWindowTool implements AgoraTool {

    private static final int DEFAULT_LIMIT = 200;

    private final EarningsService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetEarningsWindowTool(EarningsService service) { this.service = service; }

    public String name() { return "get_earnings_window"; }
    public String description() {
        return "Market-wide earnings events reported in a date window; one row per company with its symbol.";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("from").put("type", "string").put("description", "start date (YYYY-MM-DD), inclusive; default now-30d");
        props.putObject("to").put("type", "string").put("description", "end date (YYYY-MM-DD), inclusive; default now");
        props.putObject("limit").put("type", "integer").put("description", "max rows (default 200)");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        LocalDate from, to;
        try {
            String fromRaw = args == null ? null : args.path("from").asString(null);
            String toRaw = args == null ? null : args.path("to").asString(null);
            from = (fromRaw == null || fromRaw.isBlank()) ? LocalDate.now().minusDays(30) : LocalDate.parse(fromRaw);
            to = (toRaw == null || toRaw.isBlank()) ? LocalDate.now() : LocalDate.parse(toRaw);
        } catch (DateTimeParseException e) {
            return ToolResult.unavailable("invalid date");
        }
        int limit = args != null && args.path("limit").isInt() && args.path("limit").asInt() > 0
                ? args.path("limit").asInt() : DEFAULT_LIMIT;
        try {
            List<EarningsEvent> events = service.earningsWindow(from, to);
            ObjectNode out = mapper.createObjectNode();
            ArrayNode arr = out.putArray("earnings");
            int n = 0;
            for (EarningsEvent e : events) {
                if (n++ >= limit) break;
                ObjectNode o = arr.addObject();
                o.put("symbol", e.symbol());
                if (e.date() != null) o.put("date", e.date().toString());
                if (e.epsEstimate() != null) o.put("epsEstimate", e.epsEstimate());
                if (e.epsActual() != null) o.put("epsActual", e.epsActual());
                if (e.epsSurprisePct() != null) o.put("epsSurprisePct", e.epsSurprisePct());
                if (e.revenueEstimate() != null) o.put("revenueEstimate", e.revenueEstimate());
                if (e.revenueActual() != null) o.put("revenueActual", e.revenueActual());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
