package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.EpsPoint;
import de.visterion.agora.fetch.split.SplitAdjuster;
import de.visterion.agora.fetch.split.SplitService;
import de.visterion.agora.fetch.split.SplitEvent;
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
        return "Reported quarterly EPS history for a company (by symbol or CIK). "
             + "adjusted=true requires 'symbol' (split-adjustment is not available for CIK-only requests).";
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
            String note = null;
            String splitAdjustment = null;
            if (wantAdjusted) {
                if (symbol != null && !symbol.isBlank()) {
                    try {
                        splits = splitService.splits(symbol);
                        adjusted = !splits.isEmpty();
                        if (!adjusted) note = "no split data available; returning as-reported";
                    } catch (RuntimeException e) {
                        // graceful fallback to as-reported (fetch failure or malformed split data) —
                        // but surface that this was a *failure*, not "no splits happened", so
                        // callers don't silently trust an unadjusted value as adjustment-complete.
                        adjusted = false;
                        splitAdjustment = "unavailable";
                        note = "split-adjustment fetch failed; returning as-reported";
                    }
                } else {
                    // cik-only requests have no ticker to look splits up by.
                    splitAdjustment = "unavailable";
                    note = "split-adjustment requires symbol; returning as-reported";
                }
            }

            ObjectNode out = mapper.createObjectNode();
            out.put("adjusted", adjusted);
            if (note != null) out.put("note", note);
            if (splitAdjustment != null) out.put("splitAdjustment", splitAdjustment);
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
                if (p.derived()) o.put("derived", true);
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
