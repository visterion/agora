package de.visterion.agora.data;

import java.math.BigDecimal;

/** A normalized FX conversion rate: 1 unit of `from` = `rate` units of `to`. */
public record FxRate(String from, String to, BigDecimal rate) {}
