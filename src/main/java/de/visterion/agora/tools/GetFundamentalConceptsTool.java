package de.visterion.agora.tools;

import de.visterion.agora.data.InstrumentResolver;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import de.visterion.agora.research.fundamentals.FundamentalConcept;
import de.visterion.agora.research.fundamentals.FundamentalsRouter;
import de.visterion.agora.research.fundamentals.SourceResult;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GetFundamentalConceptsTool implements AgoraTool {
    private final FundamentalsRouter router;
    private final InstrumentResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetFundamentalConceptsTool(FundamentalsRouter router, InstrumentResolver resolver) {
        this.router = router; this.resolver = resolver;
    }

    public String name() { return "get_fundamental_concepts"; }
    public String description() {
        return "Raw normalized company-fundamentals line items (neutral concepts + reporting currency) — "
             + "US via SEC EDGAR, non-US via Yahoo. Concept values are in each series' `unit` currency "
             + "(reporting currency, which may differ from the listing currency).";
    }
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("symbol").put("type", "string").put("description", "ticker or ISIN");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? "" : args.path("symbol").asString("");
        if (symbol.isBlank()) return ToolResult.unavailable("symbol required");
        try {
            SourceResult r = router.facts(resolver.resolve(symbol));
            ObjectNode out = mapper.createObjectNode();
            out.put("symbol", symbol);
            out.put("source", r.semantics().name());
            ObjectNode concepts = out.putObject("concepts");
            for (FundamentalConcept c : FundamentalConcept.values()) {
                ConceptSeries s = r.series(c);
                if (s.datapoints().isEmpty()) continue;
                ObjectNode cn = concepts.putObject(c.name());
                cn.put("unit", s.unit());
                var dps = cn.putArray("datapoints");
                for (ConceptDatapoint p : s.datapoints()) {
                    ObjectNode dn = dps.addObject();
                    dn.put("periodStart", p.periodStart() == null ? null : p.periodStart().toString());
                    dn.put("periodEnd", p.periodEnd() == null ? null : p.periodEnd().toString());
                    dn.put("value", p.value());
                    dn.put("filed", p.filed() == null ? null : p.filed().toString());
                }
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
