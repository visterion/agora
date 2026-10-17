package de.visterion.agora.trading;

import java.math.BigDecimal;

/**
 * A closed (fully realized) position — the broker's trade-history record of a position that
 * has already been closed, carrying the REAL average open/close fill prices and realized P/L
 * (as opposed to {@link Position}, which only covers still-open positions). {@code clientRef}
 * is nullable — not every broker echoes an external/client reference on a closed position.
 * {@code openTime}/{@code closeTime} are nullable ISO-8601 timestamps. {@code openingPositionId}
 * is a nullable Saxo *position* id (not an order id) identifying the position that was closed.
 *
 * <p>The five trailing cost fields (Saxo {@code ClosedPosition} schema) are all nullable:
 * {@code costOpening}/{@code costClosing} are the total cost of opening/closing the position in
 * INSTRUMENT currency; {@code costOpeningBase}/{@code costClosingBase} are the same two costs in
 * the client/account's BASE currency; {@code profitLossCurrencyConversion} is the P/L caused by
 * currency conversion between position open and position close. For all five, null means the
 * broker did not report the field — it is never defaulted to zero. A reported value of zero
 * (the broker explicitly sent {@code 0}) is a real zero and is kept as {@link BigDecimal#ZERO},
 * distinct from null.
 */
public record ClosedPosition(String symbol, long uic, BigDecimal openPrice, BigDecimal closePrice,
                             BigDecimal amount, BigDecimal profitLoss, String clientRef,
                             String openTime, String closeTime, Long openingPositionId,
                             BigDecimal costOpening, BigDecimal costClosing, BigDecimal costOpeningBase,
                             BigDecimal costClosingBase, BigDecimal profitLossCurrencyConversion) {

    /** Legacy 7-arg shape: timestamps + openingPositionId + cost fields all null. */
    public ClosedPosition(String symbol, long uic, BigDecimal openPrice, BigDecimal closePrice,
                          BigDecimal amount, BigDecimal profitLoss, String clientRef) {
        this(symbol, uic, openPrice, closePrice, amount, profitLoss, clientRef, null, null, null,
                null, null, null, null, null);
    }

    /** Legacy 10-arg shape (pre-cost-fields): cost fields all null. */
    public ClosedPosition(String symbol, long uic, BigDecimal openPrice, BigDecimal closePrice,
                          BigDecimal amount, BigDecimal profitLoss, String clientRef,
                          String openTime, String closeTime, Long openingPositionId) {
        this(symbol, uic, openPrice, closePrice, amount, profitLoss, clientRef, openTime, closeTime,
                openingPositionId, null, null, null, null, null);
    }
}
