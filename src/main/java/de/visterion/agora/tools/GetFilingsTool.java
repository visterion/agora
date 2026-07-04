package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.FilingRef;
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
public class GetFilingsTool implements AgoraTool {

    private final EdgarService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetFilingsTool(EdgarService service) { this.service = service; }

    public String name() { return "get_filings"; }
    public String description() {
        return "Recent SEC filings for a company (by symbol or CIK), optionally filtered by form type.";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        props.putObject("cik").put("type", "string").put("description", "SEC CIK (alternative to symbol)");
        props.putObject("formType").put("type", "string").put("description", "filter by form type, e.g. 8-K, 10-Q");
        props.putObject("from").put("type", "string").put("description", "earliest filing date ISO (YYYY-MM-DD)");
        props.putObject("to").put("type", "string").put("description", "latest filing date ISO (YYYY-MM-DD)");
        props.putObject("limit").put("type", "integer").put("description", "max filings to return; default 40");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? null : args.path("symbol").asString(null);
        String cik = args == null ? null : args.path("cik").asString(null);
        if ((symbol == null || symbol.isBlank()) && (cik == null || cik.isBlank()))
            return ToolResult.unavailable("symbol or cik required");
        String formType = args.path("formType").asString(null);
        LocalDate from;
        LocalDate to;
        try {
            String fromRaw = args.path("from").asString(null);
            from = (fromRaw == null || fromRaw.isBlank()) ? null : LocalDate.parse(fromRaw);
            String toRaw = args.path("to").asString(null);
            to = (toRaw == null || toRaw.isBlank()) ? null : LocalDate.parse(toRaw);
        } catch (DateTimeParseException e) {
            return ToolResult.unavailable("invalid date");
        }
        int limit = args.path("limit").asInt(40);
        try {
            String resolvedCik = service.resolveCik(symbol, cik);
            List<FilingRef> filings = service.filings(symbol, cik, formType, from, to, limit);
            ObjectNode out = mapper.createObjectNode();
            out.put("cik", resolvedCik);
            ArrayNode arr = out.putArray("filings");
            for (FilingRef f : filings) {
                ObjectNode o = arr.addObject();
                o.put("accession", f.accession());
                o.put("form", f.form());
                o.put("filedDate", f.filedDate() == null ? null : f.filedDate().toString());
                o.put("reportDate", f.reportDate() == null ? null : f.reportDate().toString());
                o.put("primaryDoc", f.primaryDoc());
                o.put("url", f.url());
            }
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
