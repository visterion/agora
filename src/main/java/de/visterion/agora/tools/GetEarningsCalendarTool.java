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
public class GetEarningsCalendarTool implements AgoraTool {

    private final EarningsService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetEarningsCalendarTool(EarningsService service) { this.service = service; }

    public String name() { return "get_earnings_calendar"; }
    public String description() { return "Recent and upcoming earnings events for a symbol."; }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        props.putObject("from").put("type", "string").put("description", "start date (YYYY-MM-DD), inclusive");
        props.putObject("to").put("type", "string").put("description", "end date (YYYY-MM-DD), inclusive");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? null : args.path("symbol").asString(null);
        if (symbol == null || symbol.isBlank()) return ToolResult.unavailable("no symbol provided");
        LocalDate from, to;
        try {
            String fromRaw = args.path("from").asString(null);
            String toRaw = args.path("to").asString(null);
            from = (fromRaw == null || fromRaw.isBlank()) ? LocalDate.now().minusDays(90) : LocalDate.parse(fromRaw);
            to = (toRaw == null || toRaw.isBlank()) ? LocalDate.now().plusDays(90) : LocalDate.parse(toRaw);
        } catch (DateTimeParseException e) {
            return ToolResult.unavailable("invalid date");
        }
        try {
            List<EarningsEvent> events = service.earnings(symbol, from, to);
            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", symbol);
            ArrayNode arr = out.putArray("earnings");
            for (EarningsEvent e : events) {
                ObjectNode o = arr.addObject();
                if (e.date() != null) o.put("date", e.date().toString());
                if (e.epsEstimate() != null) o.put("epsEstimate", e.epsEstimate());
                if (e.epsActual() != null) o.put("epsActual", e.epsActual());
                if (e.epsSurprisePct() != null) o.put("epsSurprisePct", e.epsSurprisePct());
                if (e.revenueEstimate() != null) o.put("revenueEstimate", e.revenueEstimate());
                if (e.revenueActual() != null) o.put("revenueActual", e.revenueActual());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            if (e.kind() == MarketDataException.Kind.NOT_FOUND) {
                ObjectNode out = mapper.createObjectNode();
                out.put("symbol", symbol);
                out.putArray("earnings");
                out.put("note", "no earnings in the requested window");
                return ToolResult.ok(out);
            }
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
