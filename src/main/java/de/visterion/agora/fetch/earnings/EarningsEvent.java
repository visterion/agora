package de.visterion.agora.fetch.earnings;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One earnings event; nullable numeric fields when not yet reported / no consensus. */
public record EarningsEvent(String symbol, LocalDate date, BigDecimal epsEstimate, BigDecimal epsActual,
                            BigDecimal epsSurprisePct, BigDecimal revenueEstimate, BigDecimal revenueActual) {}
