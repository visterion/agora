package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.EdgarService.CompanyFacts;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** US fundamentals from EDGAR us-gaap. Preserves the exact per-concept tag priority +
 *  first-non-empty-wins selection the Piotroski scorer historically used (no synonym merge).
 *  Returns COMPLETE: an absent concept means the filer genuinely reports none. */
@Component
public class EdgarFundamentalsSource implements FundamentalsSource {

    private final EdgarService edgar;
    public EdgarFundamentalsSource(EdgarService edgar) { this.edgar = edgar; }

    /** Ordered us-gaap tag priority per neutral concept — copied verbatim from the old
     *  FundamentalScoreService.first(...) call sites. */
    private static final Map<FundamentalConcept, String[]> TAGS = new EnumMap<>(FundamentalConcept.class);

    static {
        TAGS.put(FundamentalConcept.NET_INCOME, new String[]{"NetIncomeLoss"});
        TAGS.put(FundamentalConcept.OPERATING_CASH_FLOW, new String[]{
            "NetCashProvidedByUsedInOperatingActivities",
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations"});
        TAGS.put(FundamentalConcept.TOTAL_ASSETS, new String[]{"Assets"});
        TAGS.put(FundamentalConcept.LONG_TERM_DEBT, new String[]{"LongTermDebtNoncurrent", "LongTermDebt"});
        TAGS.put(FundamentalConcept.CURRENT_ASSETS, new String[]{"AssetsCurrent"});
        TAGS.put(FundamentalConcept.CURRENT_LIABILITIES, new String[]{"LiabilitiesCurrent"});
        TAGS.put(FundamentalConcept.SHARES_OUTSTANDING, new String[]{
            "CommonStockSharesOutstanding", "WeightedAverageNumberOfSharesOutstandingBasic"});
        TAGS.put(FundamentalConcept.GROSS_PROFIT, new String[]{"GrossProfit"});
        TAGS.put(FundamentalConcept.REVENUE, new String[]{
            "Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet"});
    }

    @Override
    public SourceResult facts(Instrument inst) {
        CompanyFacts f = edgar.companyFacts(inst.displaySymbol(), null); // throws MarketDataException on transient failure
        Map<FundamentalConcept, ConceptSeries> out = new EnumMap<>(FundamentalConcept.class);
        for (Map.Entry<FundamentalConcept, String[]> e : TAGS.entrySet()) {
            ConceptSeries s = firstNonEmpty(f, e.getValue());
            if (!s.datapoints().isEmpty()) out.put(e.getKey(), s);
        }
        return new SourceResult(out, AbsenceSemantics.COMPLETE);
    }

    private static ConceptSeries firstNonEmpty(CompanyFacts f, String... tags) {
        for (String tag : tags) {
            ConceptSeries s = f.series(tag);
            if (s != null && s.datapoints() != null && !s.datapoints().isEmpty()) return s;
        }
        return new ConceptSeries(null, List.of());
    }
}
