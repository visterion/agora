package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.InstrumentResolver;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import de.visterion.agora.fetch.split.SplitAdjuster;
import de.visterion.agora.fetch.split.SplitEvent;
import de.visterion.agora.fetch.split.SplitService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Computes the Piotroski (2000) F-score from routed fundamentals facts, strict + coverage:
 *  a criterion scores 1 only if it is both met and available; unavailable criteria score
 *  0 but are surfaced separately via {@link PiotroskiFScore#criteriaAvailable()}.
 *  Every cross-concept ratio (ROA, leverage, margin, turnover deltas) is only evaluated when
 *  both concepts' datapoints line up on the SAME fiscal year (see {@link AnnualFacts#sameYearAs}
 *  / {@link AnnualFacts#sameCurrentYearAs}) — a fiscal-year mismatch makes the criterion
 *  unavailable rather than mixing years into a meaningless ratio. */
@Component
public class FundamentalScoreService {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final FundamentalsRouter router;
    private final InstrumentResolver resolver;
    private final SplitService splitService;

    public FundamentalScoreService(FundamentalsRouter router,
                                   InstrumentResolver resolver,
                                   SplitService splitService) {
        this.router = router;
        this.resolver = resolver;
        this.splitService = splitService;
    }

    public PiotroskiFScore piotroski(String symbol) {
        SourceResult r = router.facts(resolver.resolve(symbol));
        boolean complete = r.semantics() == AbsenceSemantics.COMPLETE;

        AnnualFacts ni = AnnualFacts.of(r.series(FundamentalConcept.NET_INCOME));
        AnnualFacts cfo = AnnualFacts.of(r.series(FundamentalConcept.OPERATING_CASH_FLOW));
        AnnualFacts assets = AnnualFacts.ofInstant(r.series(FundamentalConcept.TOTAL_ASSETS));
        ConceptSeries ltDebtSeries = r.series(FundamentalConcept.LONG_TERM_DEBT);
        AnnualFacts ltDebt = AnnualFacts.ofInstant(ltDebtSeries);
        boolean debtFree = complete && ltDebtSeries.datapoints().isEmpty();
        AnnualFacts curA = AnnualFacts.ofInstant(r.series(FundamentalConcept.CURRENT_ASSETS));
        AnnualFacts curL = AnnualFacts.ofInstant(r.series(FundamentalConcept.CURRENT_LIABILITIES));
        AnnualFacts shares = AnnualFacts.ofInstant(r.series(FundamentalConcept.SHARES_OUTSTANDING));
        AnnualFacts gross = AnnualFacts.of(r.series(FundamentalConcept.GROSS_PROFIT));
        AnnualFacts rev = AnnualFacts.of(r.series(FundamentalConcept.REVENUE));

        Map<String, PiotroskiFScore.Criterion> criteria = new LinkedHashMap<>();

        // ROA sign only depends on NI sign (assets are never negative for a going concern), so
        // this criterion is evaluable from NI alone even when Assets data is entirely missing.
        boolean roaPositiveAvail = ni.hasCurrent();
        boolean roaPositiveMet = roaPositiveAvail && ni.current().signum() > 0;
        criteria.put("roaPositive", new PiotroskiFScore.Criterion(roaPositiveMet, roaPositiveAvail));

        boolean cfoPositiveAvail = cfo.hasCurrent();
        boolean cfoPositiveMet = cfoPositiveAvail && cfo.current().signum() > 0;
        criteria.put("cfoPositive", new PiotroskiFScore.Criterion(cfoPositiveMet, cfoPositiveAvail));

        boolean roaImprovedAvail = ni.sameYearAs(assets)
                && nonZero(assets.current()) && nonZero(assets.prior());
        boolean roaImprovedMet = roaImprovedAvail
                && divide(ni.current(), assets.current()).compareTo(divide(ni.prior(), assets.prior())) > 0;
        criteria.put("roaImproved", new PiotroskiFScore.Criterion(roaImprovedMet, roaImprovedAvail));

        boolean cfoExceedsNiAvail = cfo.sameCurrentYearAs(ni);
        boolean cfoExceedsNiMet = cfoExceedsNiAvail && cfo.current().compareTo(ni.current()) > 0;
        criteria.put("cfoExceedsNetIncome", new PiotroskiFScore.Criterion(cfoExceedsNiMet, cfoExceedsNiAvail));

        // A company with no LongTermDebt concept at all is debt-free, not "unknown" — that is
        // the best case for this criterion (0 -> 0 debt/assets ratio never increases), so it
        // scores 1 rather than being docked for missing data. Only trustworthy under COMPLETE
        // semantics: under SPARSE, an absent concept may just mean the source didn't cover it.
        boolean leverageAvail;
        boolean leverageMet;
        if (debtFree) {
            leverageAvail = true;
            leverageMet = true;
        } else {
            leverageAvail = ltDebt.sameYearAs(assets)
                    && nonZero(assets.current()) && nonZero(assets.prior());
            leverageMet = leverageAvail
                    && divide(ltDebt.current(), assets.current()).compareTo(divide(ltDebt.prior(), assets.prior())) < 0;
        }
        criteria.put("leverageDecreased", new PiotroskiFScore.Criterion(leverageMet, leverageAvail));

        boolean currentRatioAvail = curA.sameYearAs(curL)
                && nonZero(curL.current()) && nonZero(curL.prior());
        boolean currentRatioMet = currentRatioAvail
                && divide(curA.current(), curL.current()).compareTo(divide(curA.prior(), curL.prior())) > 0;
        criteria.put("currentRatioImproved", new PiotroskiFScore.Criterion(currentRatioMet, currentRatioAvail));

        boolean noNewSharesAvail = false;
        boolean noNewSharesMet = false;
        if (shares.available()) {
            try {
                List<SplitEvent> splits = splitService.splits(symbol);
                if (!complete && splits.isEmpty()) {
                    // SPARSE source: empty split list = unknown coverage, not "no split" -> unavailable.
                    noNewSharesAvail = false;
                    noNewSharesMet = false;
                } else {
                    // Splits between the prior and current fiscal-year-end inflate the raw share
                    // count without any real issuance; bring the prior count onto the current
                    // (post-split) basis before comparing, using the cumulative forward-split
                    // factor restricted to that window (factor-after-prior / factor-after-current
                    // cancels out splits that happened after the current fiscal-year-end).
                    BigDecimal factorAfterPrior = SplitAdjuster.cumulativeFactorAfter(shares.priorEnd(), splits);
                    BigDecimal factorAfterCurrent = SplitAdjuster.cumulativeFactorAfter(shares.currentEnd(), splits);
                    BigDecimal windowFactor = factorAfterPrior.divide(factorAfterCurrent, MC);
                    BigDecimal adjustedPrior = shares.prior().multiply(windowFactor, MC);
                    noNewSharesAvail = true;
                    noNewSharesMet = shares.current().compareTo(adjustedPrior) <= 0;
                }
            } catch (RuntimeException e) {
                // split data unavailable/unreliable -> do not guess; exclude the criterion
                // rather than mis-scoring a real split as share issuance (or vice versa).
                noNewSharesAvail = false;
                noNewSharesMet = false;
            }
        }
        criteria.put("noNewShares", new PiotroskiFScore.Criterion(noNewSharesMet, noNewSharesAvail));

        boolean grossMarginAvail = gross.sameYearAs(rev)
                && nonZero(rev.current()) && nonZero(rev.prior());
        boolean grossMarginMet = grossMarginAvail
                && divide(gross.current(), rev.current()).compareTo(divide(gross.prior(), rev.prior())) > 0;
        criteria.put("grossMarginImproved", new PiotroskiFScore.Criterion(grossMarginMet, grossMarginAvail));

        boolean assetTurnoverAvail = rev.sameYearAs(assets)
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
        if (ni.sameCurrentYearAs(assets) && nonZero(assets.current())) {
            raw.put("roa", divide(ni.current(), assets.current()));
        }
        if (cfo.hasCurrent()) {
            raw.put("cfo", cfo.current());
        }
        if (ni.hasCurrent()) {
            raw.put("netIncome", ni.current());
        }
        if (ni.sameCurrentYearAs(cfo) && ni.sameCurrentYearAs(assets) && nonZero(assets.current())) {
            raw.put("accrualRatio", divide(ni.current().subtract(cfo.current()), assets.current()));
        }
        if (curA.sameCurrentYearAs(curL) && nonZero(curL.current())) {
            raw.put("currentRatio", divide(curA.current(), curL.current()));
        }
        if (gross.sameCurrentYearAs(rev) && nonZero(rev.current())) {
            raw.put("grossMargin", divide(gross.current(), rev.current()));
        }
        if (rev.sameCurrentYearAs(assets) && nonZero(assets.current())) {
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
}
