package de.visterion.agora.trading;

import java.math.BigDecimal;

/**
 * An open position at the broker.
 *
 * <p>{@code side} is the direction of the position in the same vocabulary the write side
 * speaks: {@code "BUY"} for a long, {@code "SELL"} for a short. It is deliberately a
 * nullable {@link String} rather than an enum: an adapter that genuinely cannot determine
 * the direction (broker omits the field, or a flat/zero net position that has no direction)
 * reports {@code null} instead of guessing a side. Consumers must treat null as unknown, not
 * as long.
 *
 * <p>{@code qty} keeps whatever sign the broker reports (Saxo and Alpaca both return a
 * negative quantity for a short); {@code side} is derived from it and does not replace it.
 */
public record Position(String symbol, String description, BigDecimal qty, String side,
                       BigDecimal avgEntryPrice, BigDecimal marketPrice, BigDecimal marketValue,
                       BigDecimal unrealizedPl, String currency, String assetType,
                       String valueDate, int openOrdersCount) {}
