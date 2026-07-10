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
 *  Durations use ~annual (350-380d) points; instants use point-in-time facts
 *  (periodStart null) tagged fp="FY" — quarterly 10-Q snapshots must not be
 *  compared against fiscal-year durations. */
public record AnnualFacts(BigDecimal current, BigDecimal prior, boolean available, boolean hasCurrent) {

    private static final long MIN = 350, MAX = 380;

    public static AnnualFacts of(ConceptSeries series) { return pick(series, true); }
    public static AnnualFacts ofInstant(ConceptSeries series) { return pick(series, false); }

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
        LocalDate curEnd = null;
        BigDecimal cur = null, prior = null;
        for (ConceptDatapoint p : pts) {
            if (cur == null) { cur = p.value(); curEnd = p.periodEnd(); }
            else if (!p.periodEnd().equals(curEnd)) { prior = p.value(); break; }
        }
        return new AnnualFacts(cur, prior, cur != null && prior != null, cur != null);
    }

    private static boolean annual(LocalDate s, LocalDate e) {
        long d = ChronoUnit.DAYS.between(s, e);
        return d >= MIN && d <= MAX;
    }
}
