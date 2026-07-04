package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GetCompanyConceptTool implements AgoraTool {

    private final EdgarService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetCompanyConceptTool(EdgarService service) { this.service = service; }

    public String name() { return "get_company_concept"; }
    public String description() {
        return "Full reported history of any XBRL company-concept (e.g. us-gaap/Assets) for a company "
                + "by symbol or CIK, with the reporting unit and per-period datapoints.";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        props.putObject("cik").put("type", "string").put("description", "SEC CIK (alternative to symbol)");
        props.putObject("tag").put("type", "string").put("description", "XBRL concept tag, e.g. Assets, Revenues");
        props.putObject("taxonomy").put("type", "string").put("description", "XBRL taxonomy; default us-gaap");
        schema.putArray("required").add("tag");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? null : args.path("symbol").asString(null);
        String cik = args == null ? null : args.path("cik").asString(null);
        String tag = args == null ? null : args.path("tag").asString(null);
        if ((symbol == null || symbol.isBlank()) && (cik == null || cik.isBlank()))
            return ToolResult.unavailable("symbol or cik required");
        if (tag == null || tag.isBlank())
            return ToolResult.unavailable("tag required");
        String taxonomyRaw = args.path("taxonomy").asString(null);
        String taxonomy = (taxonomyRaw == null || taxonomyRaw.isBlank()) ? "us-gaap" : taxonomyRaw.trim();
        try {
            String resolvedCik = service.resolveCik(symbol, cik);
            EdgarService.ConceptSeries series = service.companyConcept(resolvedCik, null, taxonomy, tag);
            ObjectNode out = mapper.createObjectNode();
            out.put("cik", resolvedCik);
            out.put("taxonomy", taxonomy);
            out.put("tag", tag);
            out.put("unit", series.unit());
            ArrayNode arr = out.putArray("datapoints");
            for (ConceptDatapoint d : series.datapoints()) {
                ObjectNode o = arr.addObject();
                o.put("periodStart", d.periodStart() == null ? null : d.periodStart().toString());
                o.put("periodEnd", d.periodEnd() == null ? null : d.periodEnd().toString());
                o.put("value", d.value());
                if (d.fiscalYear() == null) o.putNull("fiscalYear"); else o.put("fiscalYear", d.fiscalYear());
                o.put("fiscalPeriod", d.fiscalPeriod());
                o.put("form", d.form());
                o.put("filed", d.filed() == null ? null : d.filed().toString());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
