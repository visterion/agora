package de.visterion.agora.fetch.edgar;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One XBRL company-concept datapoint. periodStart/fiscalYear/filed may be null. */
public record ConceptDatapoint(LocalDate periodStart, LocalDate periodEnd, BigDecimal value,
                               Integer fiscalYear, String fiscalPeriod, String form, LocalDate filed) {}
