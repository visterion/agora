package de.visterion.agora.research.fundamentals;

import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The two most recent fiscal-year values of a concept: current (t) and prior (t-1).
 *  {@code available} = both present; {@code hasCurrent} = at least the current year present.
 *  {@code currentEnd}/{@code priorEnd} carry the fiscal-year-end dates of those two points so
 *  callers can verify that a cross-concept ratio (e.g. NetIncome / Assets) actually compares
 *  the SAME fiscal year for both concepts rather than silently mixing years.
 *  Durations use ~annual (350-380d) points; instants use point-in-time facts
 *  (periodStart null) tagged fp="FY" — quarterly 10-Q snapshots must not be
 *  compared against fiscal-year durations. */
public record AnnualFacts(BigDecimal current, BigDecimal prior, boolean available, boolean hasCurrent,
                          LocalDate currentEnd, LocalDate priorEnd) {

    private static final long MIN = 350, MAX = 380;

    public static AnnualFacts of(ConceptSeries series) { return pick(series, true); }
    public static AnnualFacts ofInstant(ConceptSeries series) { return pick(series, false); }

    /** True when both concepts have a current AND prior fiscal-year value AND those years
     *  line up (same period-end date on both sides) — required for any cross-concept delta
     *  ratio (ROA-improved, leverage, gross margin, asset turnover). */
    public boolean sameYearAs(AnnualFacts other) {
        return available && other.available
                && currentEnd != null && currentEnd.equals(other.currentEnd)
                && priorEnd != null && priorEnd.equals(other.priorEnd);
    }

    /** True when both concepts have a current fiscal-year value for the SAME period-end —
     *  required for single-year cross-concept criteria (ROA-positive, CFO-exceeds-NI). */
    public boolean sameCurrentYearAs(AnnualFacts other) {
        return hasCurrent && other.hasCurrent
                && currentEnd != null && currentEnd.equals(other.currentEnd);
    }

    private static AnnualFacts pick(ConceptSeries series, boolean duration) {
        List<ConceptDatapoint> filtered = series.datapoints().stream()
                .filter(p -> p.periodEnd() != null && p.value() != null)
                .filter(p -> duration
                        ? p.periodStart() != null && annual(p.periodStart(), p.periodEnd())
                        : p.periodStart() == null && "FY".equals(p.fiscalPeriod()))
                .toList();
        // EDGAR may carry multiple facts for the same period-end (e.g. an original filing plus
        // a later restatement/amendment). Dedup by periodEnd, preferring the LATEST `filed`
        // date so restated values win over the original rather than whichever happened first
        // in list order.
        Map<LocalDate, ConceptDatapoint> byEnd = new LinkedHashMap<>();
        for (ConceptDatapoint p : filtered) {
            ConceptDatapoint existing = byEnd.get(p.periodEnd());
            if (existing == null
                    || (p.filed() != null && (existing.filed() == null || p.filed().isAfter(existing.filed())))) {
                byEnd.put(p.periodEnd(), p);
            }
        }
        List<ConceptDatapoint> pts = byEnd.values().stream()
                .sorted(Comparator.comparing(ConceptDatapoint::periodEnd).reversed())
                .toList();
        LocalDate curEnd = null, priorEnd = null;
        BigDecimal cur = null, prior = null;
        for (ConceptDatapoint p : pts) {
            if (cur == null) { cur = p.value(); curEnd = p.periodEnd(); }
            else if (!p.periodEnd().equals(curEnd)) { prior = p.value(); priorEnd = p.periodEnd(); break; }
        }
        return new AnnualFacts(cur, prior, cur != null && prior != null, cur != null, curEnd, priorEnd);
    }

    private static boolean annual(LocalDate s, LocalDate e) {
        long d = ChronoUnit.DAYS.between(s, e);
        return d >= MIN && d <= MAX;
    }
}
