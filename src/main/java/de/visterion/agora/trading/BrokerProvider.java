package de.visterion.agora.trading;

import java.math.BigDecimal;
import java.util.List;

/** A broker. Implement as a @Component. No per-call fallback (brokers are not interchangeable). */
public interface BrokerProvider {
    String name();
    OrderResult submitBracket(BracketOrderRequest req);
    OrderResult modifyBracket(String brokerOrderId, String symbol, BigDecimal newStop, BigDecimal newTarget);

    /**
     * Same as {@link #modifyBracket(String, String, BigDecimal, BigDecimal)}, but the caller may
     * NAME the exact order carrying each leg instead of letting the provider resolve it from the
     * bracket parent or from the symbol.
     *
     * <p>Why this exists: leg resolution is only unambiguous while a symbol holds ONE bracket.
     * A position built in two tranches holds two protective stops on the same instrument, and a
     * by-symbol scan then has to guess which one it is looking at. Verified on the paper book on
     * 2026-08-04: one symbol carried two working detached {@code StopIfTraded} orders for the same
     * position (one per tranche), while another symbol's second-tranche stop was still EMBEDDED in
     * its unfilled parent's related orders — an implementation must therefore look in both shapes.
     *
     * <p>Contract: when any leg id is supplied, EVERY leg being re-priced must be named. Mixing a
     * named stop with an unnamed take-profit is rejected rather than silently falling back to the
     * guessing path the caller was trying to avoid. Both ids null == the old behaviour, byte for
     * byte, so existing callers are unaffected.
     *
     * <p>The default implementation is honest about not supporting it: a provider that cannot
     * address an individual leg rejects with {@code LEG_ADDRESSING_UNSUPPORTED} instead of quietly
     * ignoring the ids and modifying whichever leg it happened to find.
     */
    default OrderResult modifyBracket(String brokerOrderId, String symbol, BigDecimal newStop,
                                      BigDecimal newTarget, String stopOrderId, String targetOrderId) {
        if (stopOrderId == null && targetOrderId == null) {
            return modifyBracket(brokerOrderId, symbol, newStop, newTarget);
        }
        return OrderResult.rejected(
                "provider " + name() + " cannot address an individual bracket leg by order id",
                "LEG_ADDRESSING_UNSUPPORTED");
    }

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
    /** Closed positions within an optional [from,to] ISO-8601 window (null = no bound). */
    default List<ClosedPosition> closedPositions(String from, String to) { return closedPositions(); }
    /** Orders within an optional [from,to] ISO-8601 window (null = no bound). Providers may route history vs open. */
    default List<Order> orders(String status, String from, String to) { return orders(status); }
    /** Whether this broker can return real closed positions (Alpaca cannot — reconstruction only). */
    default boolean supportsClosedPositions() { return true; }
    Account account();
    Order orderByClientRef(String clientRef);
    OrderResult cancel(String brokerOrderId);
    /** Cheap authenticated no-op call verifying connectivity + credentials.
     *  Throws BrokerException on failure. */
    void probe();
}
