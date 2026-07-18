package de.visterion.agora.trading;

import java.math.BigDecimal;

/**
 * A closed (fully realized) position — the broker's trade-history record of a position that
 * has already been closed, carrying the REAL average open/close fill prices and realized P/L
 * (as opposed to {@link Position}, which only covers still-open positions). {@code clientRef}
 * is nullable — not every broker echoes an external/client reference on a closed position.
 * {@code openTime}/{@code closeTime} are nullable ISO-8601 timestamps. {@code openingPositionId}
 * is a nullable Saxo *position* id (not an order id) identifying the position that was closed.
 */
public record ClosedPosition(String symbol, long uic, BigDecimal openPrice, BigDecimal closePrice,
                             BigDecimal amount, BigDecimal profitLoss, String clientRef,
                             String openTime, String closeTime, Long openingPositionId) {

    /** Legacy 7-arg shape: timestamps + openingPositionId null. */
    public ClosedPosition(String symbol, long uic, BigDecimal openPrice, BigDecimal closePrice,
                          BigDecimal amount, BigDecimal profitLoss, String clientRef) {
        this(symbol, uic, openPrice, closePrice, amount, profitLoss, clientRef, null, null, null);
    }
}
