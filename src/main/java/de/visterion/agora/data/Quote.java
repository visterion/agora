package de.visterion.agora.data;

import java.math.BigDecimal;

/** A neutral price quote. currency is an ISO code (e.g. "USD"). */
public record Quote(String symbol, BigDecimal price, BigDecimal dayChangePercent, String currency) {}
