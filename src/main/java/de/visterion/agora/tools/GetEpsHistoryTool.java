package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.EpsPoint;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetEpsHistoryTool implements AgoraTool {

    private final EdgarService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetEpsHistoryTool(EdgarService service) { this.service = service; }

    public String name() { return "get_eps_history"; }
    public String description() {
        return "Reported quarterly EPS history for a company (by symbol or CIK).";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        props.putObject("cik").put("type", "string").put("description", "SEC CIK (alternative to symbol)");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? null : args.path("symbol").asString(null);
        String cik = args == null ? null : args.path("cik").asString(null);
        if ((symbol == null || symbol.isBlank()) && (cik == null || cik.isBlank()))
            return ToolResult.unavailable("symbol or cik required");
        try {
            List<EpsPoint> eps = service.epsHistory(symbol, cik);
            ObjectNode out = mapper.createObjectNode();
            ArrayNode arr = out.putArray("eps");
            for (EpsPoint p : eps) {
                ObjectNode o = arr.addObject();
                o.put("periodEnd", p.periodEnd() == null ? null : p.periodEnd().toString());
                o.put("periodStart", p.periodStart() == null ? null : p.periodStart().toString());
                o.put("value", p.value());
                if (p.fiscalYear() == null) o.putNull("fiscalYear"); else o.put("fiscalYear", p.fiscalYear());
                o.put("fiscalPeriod", p.fiscalPeriod());
                o.put("form", p.form());
                o.put("filed", p.filed() == null ? null : p.filed().toString());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
