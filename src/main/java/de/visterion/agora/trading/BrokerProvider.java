package de.visterion.agora.trading;

import java.math.BigDecimal;
import java.util.List;

/** A broker. Implement as a @Component. No per-call fallback (brokers are not interchangeable). */
public interface BrokerProvider {
    String name();
    OrderResult submitBracket(BracketOrderRequest req);
    OrderResult modifyBracket(String brokerOrderId, String symbol, BigDecimal newStop, BigDecimal newTarget);
    /**
     * Close (flatten) a position, in whole or in part. Exactly one of {@code fraction}
     * (0 &lt; f &le; 1) / {@code qty} may be non-null for a partial close; both null means
     * full close (equivalent to {@code fraction=1.0}). Implementations validate qty against
     * the actual position size (rejecting via {@link OrderResult#rejected} when it exceeds
     * the position) since the caller does not know the position size.
     */
    OrderResult flatten(String symbol, java.math.BigDecimal fraction, java.math.BigDecimal qty);
    List<Position> positions();
    /** Closed (already-settled) positions — real fill prices/P&amp;L from broker trade history. */
    List<ClosedPosition> closedPositions();
    List<Order> orders(String status);
    Account account();
    Order orderByClientRef(String clientRef);
    OrderResult cancel(String brokerOrderId);
    /** Cheap authenticated no-op call verifying connectivity + credentials.
     *  Throws BrokerException on failure. */
    void probe();
}
