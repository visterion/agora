package de.visterion.agora.fetch.split;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One stock-split event from Finnhub /stock/split. */
public record SplitEvent(LocalDate date, BigDecimal fromFactor, BigDecimal toFactor) {}
