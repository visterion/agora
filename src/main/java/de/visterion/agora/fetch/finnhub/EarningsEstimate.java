package de.visterion.agora.fetch.finnhub;

import java.math.BigDecimal;

/** One earnings surprise datapoint from Finnhub /stock/earnings. Nullable optional fields. */
public record EarningsEstimate(String period, BigDecimal actual, BigDecimal estimate,
                               BigDecimal surprise, BigDecimal surprisePercent) {}
