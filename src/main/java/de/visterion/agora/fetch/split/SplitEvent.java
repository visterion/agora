package de.visterion.agora.fetch.split;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One normalized stock-split event (provider-neutral). */
public record SplitEvent(LocalDate date, BigDecimal fromFactor, BigDecimal toFactor) {}
