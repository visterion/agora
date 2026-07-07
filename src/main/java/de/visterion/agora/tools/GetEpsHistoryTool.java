package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.EpsPoint;
import de.visterion.agora.fetch.finnhub.SplitAdjuster;
import de.visterion.agora.fetch.finnhub.SplitEvent;
import de.visterion.agora.fetch.finnhub.SplitService;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

@Component
public class GetEpsHistoryTool implements AgoraTool {

    private final EdgarService service;
    private final SplitService splitService;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetEpsHistoryTool(EdgarService service, SplitService splitService) {
        this.service = service;
        this.splitService = splitService;
    }

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
        props.putObject("adjusted").put("type", "boolean")
             .put("description", "split-adjust reported EPS (default false = as-reported)");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? null : args.path("symbol").asString(null);
        String cik = args == null ? null : args.path("cik").asString(null);
        if ((symbol == null || symbol.isBlank()) && (cik == null || cik.isBlank()))
            return ToolResult.unavailable("symbol or cik required");
        try {
            List<EpsPoint> eps = service.epsHistory(symbol, cik);

            boolean wantAdjusted = args.path("adjusted").asBoolean(false);
            List<SplitEvent> splits = List.of();
            boolean adjusted = false;
            if (wantAdjusted && symbol != null && !symbol.isBlank()) {
                try {
                    splits = splitService.splits(symbol);
                    adjusted = !splits.isEmpty();
                } catch (RuntimeException e) {
                    adjusted = false; // graceful fallback to as-reported (fetch failure or malformed split data)
                }
            }

            ObjectNode out = mapper.createObjectNode();
            out.put("adjusted", adjusted);
            if (wantAdjusted && !adjusted)
                out.put("note", "no split data available; returning as-reported");
            ArrayNode arr = out.putArray("eps");
            for (EpsPoint p : eps) {
                ObjectNode o = arr.addObject();
                o.put("periodEnd", p.periodEnd() == null ? null : p.periodEnd().toString());
                o.put("periodStart", p.periodStart() == null ? null : p.periodStart().toString());
                BigDecimal value = p.value();
                if (adjusted && p.periodEnd() != null && value != null) {
                    BigDecimal factor = SplitAdjuster.cumulativeFactorAfter(p.periodEnd(), splits);
                    o.put("value", SplitAdjuster.adjust(value, factor));
                    o.put("adjustmentFactor", factor);
                } else {
                    o.put("value", value);
                }
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
