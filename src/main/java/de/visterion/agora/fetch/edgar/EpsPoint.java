package de.visterion.agora.fetch.edgar;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One reported EPS datapoint from EDGAR companyconcept. Nullable optional fields.
 *  {@code derived} is true when this point was computed as FY - (Q1+Q2+Q3) rather than
 *  reported directly by EDGAR (see EdgarService quarterly-series derivation). */
public record EpsPoint(LocalDate periodEnd, LocalDate periodStart, BigDecimal value,
                       Integer fiscalYear, String fiscalPeriod, String form, LocalDate filed,
                       boolean derived) {
    public EpsPoint(LocalDate periodEnd, LocalDate periodStart, BigDecimal value,
                     Integer fiscalYear, String fiscalPeriod, String form, LocalDate filed) {
        this(periodEnd, periodStart, value, fiscalYear, fiscalPeriod, form, filed, false);
    }
}
