package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarSearchService;
import de.visterion.agora.fetch.edgar.EdgarService;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-year Form-4 transaction history for ONE company, grouped per SEC reporting owner
 * (name/CIK). Same underlying EDGAR fetch/parse (and rate-limit/deadline/truncation semantics)
 * as {@code get_form4_transactions}, restricted via the efts entity-CIK filter.
 */
@Component
public class GetForm4OwnerHistoryTool implements AgoraTool {

    private static final int DEFAULT_YEARS = 3;
    private static final int MAX_YEARS = 5;
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;

    private final EdgarSearchService searchService;
    private final EdgarService edgarService;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetForm4OwnerHistoryTool(EdgarSearchService searchService, EdgarService edgarService) {
        this.searchService = searchService;
        this.edgarService = edgarService;
    }

    public String name() { return "get_form4_owner_history"; }
    public String description() {
        return "Non-derivative SEC Form-4 transaction history for one company (by symbol or CIK) "
                + "over the last N years, grouped per reporting owner (name/CIK). Each transaction "
                + "carries date, code, shares, price, dollar value, shares owned following, and the "
                + "Rule 10b5-1(c) plan flag (null on filings predating the 2023 checkbox).";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "ticker symbol");
        props.putObject("cik").put("type", "string").put("description", "SEC CIK (alternative to symbol)");
        props.putObject("years").put("type", "integer")
                .put("description", "history window in years back from today; default " + DEFAULT_YEARS + ", max " + MAX_YEARS);
        props.putObject("limit").put("type", "integer")
                .put("description", "max transactions to return; default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT);
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String symbol = args == null ? null : args.path("symbol").asString(null);
        String cik = args == null ? null : args.path("cik").asString(null);
        if ((symbol == null || symbol.isBlank()) && (cik == null || cik.isBlank()))
            return ToolResult.unavailable("symbol or cik required");

        int years;
        int limit;
        try {
            Integer yearsArg = ToolParams.optionalInt(args, "years");
            years = yearsArg == null ? DEFAULT_YEARS : yearsArg;
            Integer limitArg = ToolParams.optionalInt(args, "limit");
            limit = limitArg == null ? DEFAULT_LIMIT : limitArg;
        } catch (InvalidArgumentException e) {
            return ToolResult.unavailable(e.getMessage());
        }
        years = Math.clamp(years, 1, MAX_YEARS);
        limit = Math.clamp(limit, 1, MAX_LIMIT);

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusYears(years);
        try {
            String resolvedCik = edgarService.resolveCik(symbol, cik);
            EdgarSearchService.Form4Result result = searchService.form4TransactionsByCik(resolvedCik, from, to, limit);

            // Group per reporting owner; key on the owner CIK when the filing carries one (names
            // vary in casing/suffixes across filings), fall back to the name otherwise.
            // Multi-filer filings (e.g. a trust plus its individual trustee): the parser emits
            // filerCik = FIRST reportingOwner and filerName = join of ALL owners, so such filings
            // group under the first owner's CIK, and an owner's `name` can differ between solo
            // filings ("Doe Jane") and joint ones ("Doe Jane, Doe Family Trust"). The CIK is the
            // stable identity; treat `name` as display-only.
            Map<String, List<Form4Transaction>> byOwner = new LinkedHashMap<>();
            for (Form4Transaction t : result.transactions()) {
                String key = t.filerCik() == null || t.filerCik().isEmpty() ? "name:" + t.filerName() : "cik:" + t.filerCik();
                byOwner.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }

            ObjectNode out = mapper.createObjectNode();
            out.put("cik", resolvedCik);
            out.put("from", from.toString());
            out.put("to", to.toString());
            ArrayNode ownersArr = out.putArray("owners");
            for (List<Form4Transaction> txs : byOwner.values()) {
                txs.sort(Comparator.comparing(Form4Transaction::transactionDate,
                        Comparator.nullsLast(Comparator.reverseOrder())));
                Form4Transaction first = txs.get(0);
                ObjectNode owner = ownersArr.addObject();
                owner.put("name", first.filerName());
                owner.put("cik", first.filerCik());
                owner.put("role", firstNonEmptyRole(txs));
                ArrayNode txArr = owner.putArray("transactions");
                for (Form4Transaction t : txs) {
                    ObjectNode o = txArr.addObject();
                    o.put("transactionDate", t.transactionDate() == null ? null : t.transactionDate().toString());
                    o.put("code", t.code());
                    o.put("acquiredDisposedCode", t.acquiredDisposedCode());
                    o.put("form", t.form());
                    o.put("shares", t.shares());
                    o.put("price", t.price());
                    o.put("dollarValue", t.dollarValue());
                    o.put("sharesOwnedFollowing", t.sharesOwnedFollowing());
                    // Tri-state: true/false = explicit 10b5-1(c) checkbox, null = filing predates
                    // the 2023 checkbox (unknown) — consumers must not read null as false.
                    o.put("aff10b5One", t.aff10b5One());
                }
            }
            out.put("truncated", result.truncated());
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }

    private static String firstNonEmptyRole(List<Form4Transaction> txs) {
        for (Form4Transaction t : txs) {
            if (t.filerRole() != null && !t.filerRole().isEmpty()) return t.filerRole();
        }
        return "";
    }
}
