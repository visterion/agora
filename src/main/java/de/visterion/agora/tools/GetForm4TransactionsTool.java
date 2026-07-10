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

    private static final int MAX_LIMIT = 100;
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
        props.putObject("limit").put("type", "integer").put("description", "max transactions to return; default 100, max " + MAX_LIMIT);
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
            limit = limitArg == null ? 100 : limitArg;
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
            }
            out.put("truncated", result.truncated());
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
