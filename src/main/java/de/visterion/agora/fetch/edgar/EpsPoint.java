package de.visterion.agora.fetch.edgar;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One reported EPS datapoint from EDGAR companyconcept. Nullable optional fields. */
public record EpsPoint(LocalDate periodEnd, LocalDate periodStart, BigDecimal value,
                       Integer fiscalYear, String fiscalPeriod, String form, LocalDate filed) {}
