package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarSearchService;
import de.visterion.agora.fetch.edgar.FilingHit;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolParams;
import de.visterion.agora.tool.ToolParams.InvalidArgumentException;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Component
public class GetSearchFilingsTool implements AgoraTool {

    /** Aligned with get_earnings_window and get_form4_transactions; and DERIVED from the EFTS-side
     *  ceiling this service can actually reach, so the two can no longer drift: a larger number
     *  here would be silently capped by the service. */
    private static final int MAX_LIMIT = EdgarSearchService.HARD_FETCH_CAP;
    /** Deliberately NOT raised with MAX_LIMIT — see GetForm4TransactionsTool.DEFAULT_LIMIT. */
    private static final int DEFAULT_LIMIT = 100;
    private final EdgarSearchService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetSearchFilingsTool(EdgarSearchService service) { this.service = service; }

    public String name() { return "search_filings"; }
    public String description() {
        return "SEC EDGAR full-text filing search by form type(s) and date window, "
                + "optionally with a free-text query. Returns matching filings across all companies.";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode forms = props.putObject("forms");
        forms.put("type", "array").put("description",
                "form type(s), e.g. [\"8-K\",\"10-K\"] or a comma-separated string; required");
        forms.putObject("items").put("type", "string");
        props.putObject("query").put("type", "string").put("description", "optional free-text query");
        props.putObject("from").put("type", "string").put("description", "earliest filing date ISO (YYYY-MM-DD); default now-30d");
        props.putObject("to").put("type", "string").put("description", "latest filing date ISO (YYYY-MM-DD); default now");
        props.putObject("limit").put("type", "integer").put("description",
                "max hits to return; default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT
                + ". A market-wide form/date window can hold far more filings than the default; "
                + "pass the maximum explicitly for market-wide scans. A cut result always reports "
                + "truncated=true.");
        schema.putArray("required").add("forms");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        List<String> forms = parseForms(args == null ? null : args.path("forms"));
        if (forms.isEmpty()) return ToolResult.unavailable("forms required");

        String query = args.path("query").asString(null);
        LocalDate to;
        LocalDate from;
        try {
            String toRaw = args.path("to").asString(null);
            to = (toRaw == null || toRaw.isBlank()) ? LocalDate.now() : LocalDate.parse(toRaw);
            String fromRaw = args.path("from").asString(null);
            from = (fromRaw == null || fromRaw.isBlank()) ? to.minusDays(30) : LocalDate.parse(fromRaw);
        } catch (DateTimeParseException e) {
            return ToolResult.unavailable("invalid date");
        }
        if (from.isAfter(to)) return ToolResult.unavailable("from must not be after to");

        int limit;
        try {
            Integer limitArg = ToolParams.optionalInt(args, "limit");
            limit = limitArg == null ? DEFAULT_LIMIT : limitArg;
        } catch (InvalidArgumentException e) {
            return ToolResult.unavailable(e.getMessage());
        }
        limit = Math.clamp(limit, 1, MAX_LIMIT);

        try {
            EdgarSearchService.SearchResult found = service.searchResult(forms, query, from, to, limit);
            List<FilingHit> hits = found.hits();
            ObjectNode out = mapper.createObjectNode();
            ArrayNode arr = out.putArray("filings");
            for (FilingHit h : hits) {
                ObjectNode o = arr.addObject();
                o.put("ticker", h.ticker());
                o.put("company", h.company());
                o.put("form", h.form());
                o.put("filedDate", h.filedDate() == null ? null : h.filedDate().toString());
                o.put("accession", h.accession());
                o.put("url", h.url());
            }
            // Two independent cuts, either of which makes the window partial: the caller's own
            // `limit` filled up, or the service stopped paginating early (HARD_FETCH_CAP, EFTS
            // error body). The row count alone cannot see the second one — MAX_LIMIT equals
            // HARD_FETCH_CAP, so one hit dropped by the parser yields 999 and would otherwise
            // report a 50,000-filing window as complete.
            out.put("truncated", found.capped() || hits.size() >= limit);
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }

    private static List<String> parseForms(JsonNode node) {
        List<String> forms = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) return forms;
        if (node.isArray()) {
            for (JsonNode f : node) {
                String s = f.asString("").trim();
                if (!s.isEmpty()) forms.add(s);
            }
        } else {
            String raw = node.asString("");
            for (String part : raw.split(",")) {
                String s = part.trim();
                if (!s.isEmpty()) forms.add(s);
            }
        }
        return forms;
    }
}
