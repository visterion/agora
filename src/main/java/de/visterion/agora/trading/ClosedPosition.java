package de.visterion.agora.trading;

import java.math.BigDecimal;

/**
 * A closed (fully realized) position — the broker's trade-history record of a position that
 * has already been closed, carrying the REAL average open/close fill prices and realized P/L
 * (as opposed to {@link Position}, which only covers still-open positions). {@code clientRef}
 * is nullable — not every broker echoes an external/client reference on a closed position.
 */
public record ClosedPosition(String symbol, long uic, BigDecimal openPrice, BigDecimal closePrice,
                             BigDecimal amount, BigDecimal profitLoss, String clientRef) {}
