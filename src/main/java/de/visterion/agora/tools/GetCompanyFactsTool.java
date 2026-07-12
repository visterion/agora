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

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches several XBRL us-gaap company-concepts for one company in a SINGLE upstream
 * companyfacts request, then returns only the requested {@code tags}. Each tag's payload
 * is shaped exactly like {@link GetCompanyConceptTool} ({@code unit} + per-period
 * {@code datapoints}) so a consumer can parse them identically. Prefer this over N
 * {@code get_company_concept} calls when several concepts are needed for one company.
 */
@Component
public class GetCompanyFactsTool implements AgoraTool {

    private final EdgarService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetCompanyFactsTool(EdgarService service) { this.service = service; }

    public String name() { return "get_company_facts"; }
    public String description() {
        return "Reported history of several XBRL us-gaap company-concepts for a company "
                + "(by symbol or CIK) in one fetch. Input `tags` selects the concepts (e.g. "
                + "Assets, LiabilitiesCurrent). Returns per requested tag its reporting unit "
                + "and per-period datapoints — same shape as get_company_concept — under "
                + "`facts`. Cheaper than N get_company_concept calls for one company.";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        props.putObject("cik").put("type", "string").put("description", "SEC CIK (alternative to symbol)");
        ObjectNode tags = props.putObject("tags");
        tags.put("type", "array").put("description", "us-gaap XBRL concept tags to return, e.g. Assets, LiabilitiesCurrent");
        tags.putObject("items").put("type", "string");
        schema.putArray("required").add("tags");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? null : args.path("symbol").asString(null);
        String cik = args == null ? null : args.path("cik").asString(null);
        if ((symbol == null || symbol.isBlank()) && (cik == null || cik.isBlank()))
            return ToolResult.unavailable("symbol or cik required");

        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = args == null ? null : args.path("tags");
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode t : tagsNode) {
                String tag = t.asString(null);
                if (tag != null && !tag.isBlank()) tags.add(tag.trim());
            }
        }
        if (tags.isEmpty())
            return ToolResult.unavailable("tags required");

        try {
            String resolvedCik = service.resolveCik(symbol, cik);
            EdgarService.CompanyFacts facts = service.companyFacts(symbol, cik);
            ObjectNode out = mapper.createObjectNode();
            out.put("cik", resolvedCik);
            out.put("taxonomy", "us-gaap");
            ObjectNode byTag = out.putObject("facts");
            for (String tag : tags) {
                EdgarService.ConceptSeries series = facts.series(tag);
                ObjectNode tagNode = byTag.putObject(tag);
                tagNode.put("unit", series.unit());
                ArrayNode arr = tagNode.putArray("datapoints");
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
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
