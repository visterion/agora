package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.research.fundamentals.FundamentalScoreService;
import de.visterion.agora.research.fundamentals.PiotroskiFScore;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class GetFundamentalScoreTool implements AgoraTool {

    private final FundamentalScoreService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetFundamentalScoreTool(FundamentalScoreService service) { this.service = service; }

    public String name() { return "get_fundamental_score"; }
    public String description() {
        return "Standardized fundamental-health scores (Piotroski F-score) computed from SEC XBRL company facts.";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? "" : args.path("symbol").asString("");
        if (symbol.isBlank()) {
            return ToolResult.unavailable("symbol required");
        }

        try {
            PiotroskiFScore s = service.piotroski(symbol);

            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", symbol);
            ObjectNode scores = out.putObject("scores");
            ObjectNode piotroskiF = scores.putObject("piotroskiF");
            piotroskiF.put("score", s.score());
            piotroskiF.put("criteriaAvailable", s.criteriaAvailable());

            ObjectNode criteria = piotroskiF.putObject("criteria");
            for (Map.Entry<String, PiotroskiFScore.Criterion> e : s.criteria().entrySet()) {
                ObjectNode c = criteria.putObject(e.getKey());
                c.put("met", e.getValue().met());
                c.put("available", e.getValue().available());
            }

            ObjectNode raw = piotroskiF.putObject("raw");
            for (Map.Entry<String, BigDecimal> e : s.raw().entrySet()) {
                raw.put(e.getKey(), e.getValue());
            }

            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
