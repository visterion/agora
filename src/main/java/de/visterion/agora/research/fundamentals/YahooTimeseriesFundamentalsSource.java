package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.LongSupplier;

/** Non-US fundamentals from Yahoo fundamentals-timeseries. SPARSE: an absent concept means
 *  Yahoo does not cover it, not that the value is zero. */
@Component
public class YahooTimeseriesFundamentalsSource implements FundamentalsSource {

    private record Mapping(FundamentalConcept concept, boolean instant) {}
    private static final Map<String, Mapping> TYPE = new LinkedHashMap<>();
    static {
        TYPE.put("annualTotalAssets", new Mapping(FundamentalConcept.TOTAL_ASSETS, true));
        TYPE.put("annualCurrentAssets", new Mapping(FundamentalConcept.CURRENT_ASSETS, true));
        TYPE.put("annualCurrentLiabilities", new Mapping(FundamentalConcept.CURRENT_LIABILITIES, true));
        TYPE.put("annualLongTermDebt", new Mapping(FundamentalConcept.LONG_TERM_DEBT, true));
        TYPE.put("annualTotalDebt", new Mapping(FundamentalConcept.TOTAL_DEBT, true));
        TYPE.put("annualTotalLiabilitiesNetMinorityInterest", new Mapping(FundamentalConcept.TOTAL_LIABILITIES, true));
        TYPE.put("annualRetainedEarnings", new Mapping(FundamentalConcept.RETAINED_EARNINGS, true));
        TYPE.put("annualOrdinarySharesNumber", new Mapping(FundamentalConcept.SHARES_OUTSTANDING, true));
        TYPE.put("annualShareIssued", new Mapping(FundamentalConcept.SHARES_OUTSTANDING, true)); // fallback if OrdinarySharesNumber empty
        TYPE.put("annualEBIT", new Mapping(FundamentalConcept.EBIT, false));
        TYPE.put("annualTotalRevenue", new Mapping(FundamentalConcept.REVENUE, false));
        TYPE.put("annualGrossProfit", new Mapping(FundamentalConcept.GROSS_PROFIT, false));
        TYPE.put("annualNetIncome", new Mapping(FundamentalConcept.NET_INCOME, false));
        TYPE.put("annualOperatingCashFlow", new Mapping(FundamentalConcept.OPERATING_CASH_FLOW, false));
    }
    private static final String TYPES_CSV = String.join(",", TYPE.keySet());

    private final YahooCrumbClient client;
    private final TtlCache<String, SourceResult> cache;
    private final TtlCache<String, Boolean> negativeIsin;

    // Spring constructor — no LongSupplier bean exists, so default to the wall clock.
    // Explicitly @Autowired: with the package-private 3-arg test constructor also present,
    // Spring can no longer infer a single usable constructor on its own.
    @org.springframework.beans.factory.annotation.Autowired
    public YahooTimeseriesFundamentalsSource(
            YahooCrumbClient client,
            @Value("${agora.data.cache.ttl.fundamentals-seconds:21600}") long ttlSeconds) {
        this(client, ttlSeconds, System::currentTimeMillis);
    }

    // Test constructor — inject a deterministic clock.
    YahooTimeseriesFundamentalsSource(YahooCrumbClient client, long ttlSeconds, LongSupplier nowMillis) {
        this.client = client;
        this.cache = new TtlCache<>(ttlSeconds * 1000L, 2048, nowMillis);
        this.negativeIsin = new TtlCache<>(600_000L, 2048, nowMillis); // 10-min ISIN miss cache
    }

    @Override
    public SourceResult facts(Instrument inst) {
        String input = inst.displaySymbol();
        String symbol = input;
        if (Instrument.isIsin(input)) {
            if (negativeIsin.peek(input).isPresent())
                throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no yahoo ticker for ISIN " + input, null);
            Optional<String> resolved = client.searchIsin(input);
            if (resolved.isEmpty()) {
                negativeIsin.put(input, Boolean.TRUE);
                throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no yahoo ticker for ISIN " + input, null);
            }
            symbol = resolved.get();
        }
        final String yahooSymbol = symbol;
        return cache.get("yhf:" + yahooSymbol, () -> build(yahooSymbol)); // throwing loader caches nothing
    }

    private SourceResult build(String symbol) {
        JsonNode result = client.timeseries(symbol, TYPES_CSV).path("timeseries").path("result");
        Map<FundamentalConcept, List<ConceptDatapoint>> byConcept = new EnumMap<>(FundamentalConcept.class);
        Map<FundamentalConcept, String> unitByConcept = new EnumMap<>(FundamentalConcept.class);

        for (JsonNode series : result) {
            String type = series.path("meta").path("type").path(0).asString("");
            Mapping m = TYPE.get(type);
            if (m == null) continue;
            // SHARES_OUTSTANDING fallback: annualShareIssued only wins if OrdinarySharesNumber
            // hasn't already populated the concept from an earlier entry in this result array.
            if (m.concept() == FundamentalConcept.SHARES_OUTSTANDING
                    && "annualShareIssued".equals(type)
                    && byConcept.containsKey(FundamentalConcept.SHARES_OUTSTANDING)) {
                continue;
            }
            List<JsonNode> rows = new ArrayList<>();
            for (JsonNode row : series.path(type)) if (!row.isNull()) rows.add(row);
            rows.removeIf(r -> r.path("reportedValue").path("raw").isMissingNode() || r.path("reportedValue").path("raw").isNull());
            if (rows.isEmpty()) continue;
            rows.sort(Comparator.comparing(r -> r.path("asOfDate").asString("")));
            String latestCurrency = rows.get(rows.size() - 1).path("currencyCode").asString(null);
            List<ConceptDatapoint> pts = new ArrayList<>();
            for (JsonNode row : rows) {
                String cur = row.path("currencyCode").asString(null);
                if (latestCurrency != null && !latestCurrency.equals(cur)) continue; // mixed-currency trim
                LocalDate asOf = LocalDate.parse(row.path("asOfDate").asString());
                BigDecimal value = row.path("reportedValue").path("raw").decimalValue();
                LocalDate start = m.instant() ? null : asOf.minusYears(1);
                pts.add(new ConceptDatapoint(start, asOf, value, asOf.getYear(), "FY", null, asOf));
            }
            if (!pts.isEmpty()) {
                byConcept.put(m.concept(), pts);
                unitByConcept.put(m.concept(), latestCurrency);
            }
        }
        Map<FundamentalConcept, ConceptSeries> concepts = new EnumMap<>(FundamentalConcept.class);
        for (var e : byConcept.entrySet()) {
            concepts.put(e.getKey(), new ConceptSeries(unitByConcept.get(e.getKey()), e.getValue()));
        }
        return new SourceResult(concepts, AbsenceSemantics.SPARSE);
    }
}
