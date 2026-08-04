package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarSearchService;
import de.visterion.agora.fetch.edgar.Form4Transaction;
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
import java.util.List;

@Component
public class GetForm4TransactionsTool implements AgoraTool {

    /** Aligned with get_earnings_window and search_filings; also the EFTS-side ceiling this
     *  service can actually reach (EdgarSearchService.HARD_FETCH_CAP). */
    private static final int MAX_LIMIT = 1000;
    /** Deliberately NOT raised with MAX_LIMIT: a default of 1000 would make every caller pay a
     *  market-wide scan. Callers that scan the whole market must ask for the maximum explicitly;
     *  a default-sized result that was cut still reports truncated=true. */
    private static final int DEFAULT_LIMIT = 100;
    private final EdgarSearchService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetForm4TransactionsTool(EdgarSearchService service) { this.service = service; }

    public String name() { return "get_form4_transactions"; }
    public String description() {
        return "Non-derivative SEC Form-4 transactions (statements of changes in beneficial "
                + "ownership) filed across all companies in a date window.";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("from").put("type", "string").put("description", "earliest filing date ISO (YYYY-MM-DD); default now-30d");
        props.putObject("to").put("type", "string").put("description", "latest filing date ISO (YYYY-MM-DD); default now");
        props.putObject("limit").put("type", "integer").put("description",
                "max transactions to return; default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT
                + ". A market-wide window (no CIK filter) holds several thousand Form-4 filings "
                + "per DAY, so pass the maximum explicitly for market-wide scans — the default is "
                + "sized for narrow queries and will return only the most recent filings, with "
                + "truncated=true.");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        LocalDate to;
        LocalDate from;
        try {
            String toRaw = args == null ? null : args.path("to").asString(null);
            to = (toRaw == null || toRaw.isBlank()) ? LocalDate.now() : LocalDate.parse(toRaw);
            String fromRaw = args == null ? null : args.path("from").asString(null);
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
            EdgarSearchService.Form4Result result = service.form4Transactions(from, to, limit);
            ObjectNode out = mapper.createObjectNode();
            ArrayNode arr = out.putArray("transactions");
            for (Form4Transaction t : result.transactions()) {
                ObjectNode o = arr.addObject();
                o.put("ticker", t.ticker());
                o.put("filerName", t.filerName());
                o.put("filerRole", t.filerRole());
                o.put("transactionDate", t.transactionDate() == null ? null : t.transactionDate().toString());
                o.put("shares", t.shares());
                o.put("dollarValue", t.dollarValue());
                o.put("code", t.code());
                o.put("acquiredDisposedCode", t.acquiredDisposedCode());
                o.put("form", t.form());
                o.put("price", t.price());
                o.put("sharesOwnedFollowing", t.sharesOwnedFollowing());
                // Tri-state: true/false = explicit 10b5-1(c) checkbox, null = filing predates
                // the 2023 checkbox (unknown) — consumers must not read null as false.
                o.put("aff10b5One", t.aff10b5One());
                o.put("filerCik", t.filerCik());
            }
            out.put("truncated", result.truncated());
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
