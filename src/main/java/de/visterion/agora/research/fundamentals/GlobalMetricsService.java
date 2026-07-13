package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.FxService;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import de.visterion.agora.fetch.finnhub.Fundamentals;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal; import java.math.MathContext;
import java.util.Comparator; import java.util.Optional;

@Component
public class GlobalMetricsService {
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final FundamentalsRouter router;
    private final MarketDataService marketData;   // may be exercised in later tasks
    private final FxService fx;
    private final ObjectMapper mapper = new ObjectMapper();

    public GlobalMetricsService(FundamentalsRouter router, MarketDataService marketData, FxService fx) {
        this.router = router; this.marketData = marketData; this.fx = fx;
    }

    public Fundamentals metrics(Instrument inst) {
        SourceResult r = router.facts(inst);                 // throws MarketDataException on transient failure
        if (r.concepts().isEmpty())
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no fundamentals for " + inst.displaySymbol(), null);
        ObjectNode m = mapper.createObjectNode();

        Optional<BigDecimal> ni = latest(r, FundamentalConcept.NET_INCOME), ta = latest(r, FundamentalConcept.TOTAL_ASSETS),
            rev = latest(r, FundamentalConcept.REVENUE), gp = latest(r, FundamentalConcept.GROSS_PROFIT),
            ca = latest(r, FundamentalConcept.CURRENT_ASSETS), cl = latest(r, FundamentalConcept.CURRENT_LIABILITIES),
            tl = latest(r, FundamentalConcept.TOTAL_LIABILITIES),
            debt = latest(r, FundamentalConcept.TOTAL_DEBT).or(() -> latest(r, FundamentalConcept.LONG_TERM_DEBT));

        pct(m, "roaTTM", ni, ta);
        pct(m, "grossMarginTTM", gp, rev);
        pct(m, "netProfitMarginTTM", ni, rev);
        ratio(m, "currentRatioQuarterly", ca, cl);
        Optional<BigDecimal> equity = (ta.isPresent() && tl.isPresent()) ? Optional.of(ta.get().subtract(tl.get())) : Optional.empty();
        ratio(m, "totalDebt/totalEquityQuarterly", debt, equity);
        // OHLC/quote/FX-dependent metrics are added in Tasks 4-7.
        return new Fundamentals(inst.displaySymbol(), m);
    }

    private Optional<BigDecimal> latest(SourceResult r, FundamentalConcept c) {
        return r.series(c).datapoints().stream()
                .max(Comparator.comparing(ConceptDatapoint::periodEnd)).map(ConceptDatapoint::value);
    }
    Optional<String> reportingUnit(SourceResult r) {
        for (FundamentalConcept c : new FundamentalConcept[]{FundamentalConcept.TOTAL_ASSETS,
                FundamentalConcept.TOTAL_LIABILITIES, FundamentalConcept.REVENUE}) {
            String u = r.series(c).unit(); if (u != null) return Optional.of(u);
        }
        return Optional.empty();
    }
    private void pct(ObjectNode m, String key, Optional<BigDecimal> num, Optional<BigDecimal> den) {
        if (num.isPresent() && den.isPresent() && den.get().signum() != 0)
            m.put(key, num.get().divide(den.get(), MC).multiply(HUNDRED));
    }
    private void ratio(ObjectNode m, String key, Optional<BigDecimal> num, Optional<BigDecimal> den) {
        if (num.isPresent() && den.isPresent() && den.get().signum() != 0)
            m.put(key, num.get().divide(den.get(), MC));
    }
}
