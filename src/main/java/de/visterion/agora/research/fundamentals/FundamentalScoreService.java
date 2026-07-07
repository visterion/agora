package de.visterion.agora.research.fundamentals;

import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.EdgarService.CompanyFacts;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Computes the Piotroski (2000) F-score from EDGAR company facts, strict + coverage:
 *  a criterion scores 1 only if it is both met and available; unavailable criteria score
 *  0 but are surfaced separately via {@link PiotroskiFScore#criteriaAvailable()}. */
@Component
public class FundamentalScoreService {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final EdgarService edgar;

    public FundamentalScoreService(EdgarService edgar) {
        this.edgar = edgar;
    }

    public PiotroskiFScore piotroski(String symbol) {
        CompanyFacts f = edgar.companyFacts(symbol, null);

        AnnualFacts ni = AnnualFacts.of(first(f, "NetIncomeLoss"));
        AnnualFacts cfo = AnnualFacts.of(first(f, "NetCashProvidedByUsedInOperatingActivities"));
        AnnualFacts assets = AnnualFacts.ofInstant(first(f, "Assets"));
        AnnualFacts ltDebt = AnnualFacts.ofInstant(first(f, "LongTermDebtNoncurrent", "LongTermDebt"));
        AnnualFacts curA = AnnualFacts.ofInstant(first(f, "AssetsCurrent"));
        AnnualFacts curL = AnnualFacts.ofInstant(first(f, "LiabilitiesCurrent"));
        AnnualFacts shares = AnnualFacts.ofInstant(
                first(f, "CommonStockSharesOutstanding", "WeightedAverageNumberOfSharesOutstandingBasic"));
        AnnualFacts gross = AnnualFacts.of(first(f, "GrossProfit"));
        AnnualFacts rev = AnnualFacts.of(
                first(f, "Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet"));

        Map<String, PiotroskiFScore.Criterion> criteria = new LinkedHashMap<>();

        boolean roaPositiveAvail = ni.hasCurrent() && assets.hasCurrent() && nonZero(assets.current());
        boolean roaPositiveMet = roaPositiveAvail && ni.current().signum() > 0;
        criteria.put("roaPositive", new PiotroskiFScore.Criterion(roaPositiveMet, roaPositiveAvail));

        boolean cfoPositiveAvail = cfo.hasCurrent();
        boolean cfoPositiveMet = cfoPositiveAvail && cfo.current().signum() > 0;
        criteria.put("cfoPositive", new PiotroskiFScore.Criterion(cfoPositiveMet, cfoPositiveAvail));

        boolean roaImprovedAvail = ni.available() && assets.available()
                && nonZero(assets.current()) && nonZero(assets.prior());
        boolean roaImprovedMet = roaImprovedAvail
                && divide(ni.current(), assets.current()).compareTo(divide(ni.prior(), assets.prior())) > 0;
        criteria.put("roaImproved", new PiotroskiFScore.Criterion(roaImprovedMet, roaImprovedAvail));

        boolean cfoExceedsNiAvail = cfo.hasCurrent() && ni.hasCurrent();
        boolean cfoExceedsNiMet = cfoExceedsNiAvail && cfo.current().compareTo(ni.current()) > 0;
        criteria.put("cfoExceedsNetIncome", new PiotroskiFScore.Criterion(cfoExceedsNiMet, cfoExceedsNiAvail));

        boolean leverageAvail = ltDebt.available() && assets.available()
                && nonZero(assets.current()) && nonZero(assets.prior());
        boolean leverageMet = leverageAvail
                && divide(ltDebt.current(), assets.current()).compareTo(divide(ltDebt.prior(), assets.prior())) < 0;
        criteria.put("leverageDecreased", new PiotroskiFScore.Criterion(leverageMet, leverageAvail));

        boolean currentRatioAvail = curA.available() && curL.available()
                && nonZero(curL.current()) && nonZero(curL.prior());
        boolean currentRatioMet = currentRatioAvail
                && divide(curA.current(), curL.current()).compareTo(divide(curA.prior(), curL.prior())) > 0;
        criteria.put("currentRatioImproved", new PiotroskiFScore.Criterion(currentRatioMet, currentRatioAvail));

        boolean noNewSharesAvail = shares.available();
        boolean noNewSharesMet = noNewSharesAvail && shares.current().compareTo(shares.prior()) <= 0;
        criteria.put("noNewShares", new PiotroskiFScore.Criterion(noNewSharesMet, noNewSharesAvail));

        boolean grossMarginAvail = gross.available() && rev.available()
                && nonZero(rev.current()) && nonZero(rev.prior());
        boolean grossMarginMet = grossMarginAvail
                && divide(gross.current(), rev.current()).compareTo(divide(gross.prior(), rev.prior())) > 0;
        criteria.put("grossMarginImproved", new PiotroskiFScore.Criterion(grossMarginMet, grossMarginAvail));

        boolean assetTurnoverAvail = rev.available() && assets.available()
                && nonZero(assets.current()) && nonZero(assets.prior());
        boolean assetTurnoverMet = assetTurnoverAvail
                && divide(rev.current(), assets.current()).compareTo(divide(rev.prior(), assets.prior())) > 0;
        criteria.put("assetTurnoverImproved", new PiotroskiFScore.Criterion(assetTurnoverMet, assetTurnoverAvail));

        int score = 0;
        int criteriaAvailable = 0;
        for (PiotroskiFScore.Criterion c : criteria.values()) {
            if (c.available()) {
                criteriaAvailable++;
                if (c.met()) {
                    score++;
                }
            }
        }

        Map<String, BigDecimal> raw = new LinkedHashMap<>();
        if (ni.hasCurrent() && assets.hasCurrent() && nonZero(assets.current())) {
            raw.put("roa", divide(ni.current(), assets.current()));
        }
        if (cfo.hasCurrent()) {
            raw.put("cfo", cfo.current());
        }
        if (ni.hasCurrent()) {
            raw.put("netIncome", ni.current());
        }
        if (ni.hasCurrent() && cfo.hasCurrent() && assets.hasCurrent() && nonZero(assets.current())) {
            raw.put("accrualRatio", divide(ni.current().subtract(cfo.current()), assets.current()));
        }
        if (curA.hasCurrent() && curL.hasCurrent() && nonZero(curL.current())) {
            raw.put("currentRatio", divide(curA.current(), curL.current()));
        }
        if (gross.hasCurrent() && rev.hasCurrent() && nonZero(rev.current())) {
            raw.put("grossMargin", divide(gross.current(), rev.current()));
        }
        if (rev.hasCurrent() && assets.hasCurrent() && nonZero(assets.current())) {
            raw.put("assetTurnover", divide(rev.current(), assets.current()));
        }

        return new PiotroskiFScore(score, criteriaAvailable, criteria, raw);
    }

    private static boolean nonZero(BigDecimal value) {
        return value != null && value.signum() != 0;
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, MC);
    }

    private static ConceptSeries first(CompanyFacts f, String... tags) {
        for (String tag : tags) {
            ConceptSeries series = f.series(tag);
            if (series != null && series.datapoints() != null && !series.datapoints().isEmpty()) {
                return series;
            }
        }
        return new ConceptSeries(null, List.of());
    }
}
