package de.visterion.agora.trading;

import java.math.BigDecimal;

/**
 * A bracket submission: entry, a mandatory protective stop, and an optional take-profit.
 *
 * <p>{@code takeProfitLimit} is <b>optional</b> (since 2026-07-25): {@code null} means
 * "entry + stop-loss, no take-profit leg". Saxo supports that shape (the child {@code Orders}
 * array then carries the stop only — the same shape the far-stop fallback produces); Alpaca
 * rejects it, because its {@code order_class=bracket} requires both legs.
 */
public record BracketOrderRequest(String symbol, String side, BigDecimal qty, String type,
                                  String timeInForce, BigDecimal limitPrice,
                                  BigDecimal stopLossStop, BigDecimal stopLossLimit,
                                  BigDecimal takeProfitLimit, String clientRef) {}
