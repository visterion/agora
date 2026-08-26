package de.visterion.agora.trading;

public class BrokerException extends RuntimeException {
    /**
     * {@code NOT_FOUND} is deliberately generic — an HTTP 404 on some unrelated resource
     * (a session read, a related-orders lookup, a leg id) reached via {@code readError}/
     * {@code handleWriteError}. It says only "the broker returned 404 here", nothing about
     * whether a position exists.
     *
     * <p>{@code NO_POSITION} is the one narrow, deliberate exception: thrown ONLY at the two
     * sites that make a definite determination that there is no open position for the symbol
     * being flattened ({@code SaxoBrokerProvider.resolveNetPosition}'s empty net-positions scan,
     * {@code AlpacaBrokerProvider.fetchPositionQtyOrThrow}'s scoped 404 on
     * {@code GET /positions/{symbol}}). Both providers reach this determination BEFORE any write
     * is attempted, so it never needs to interact with a write-failure rollback path. Distinct
     * from {@code NOT_FOUND} on purpose: folding a generic 404 into "the position is gone" was
     * exactly the defect this Kind exists to end (see {@code FlattenTool}).
     */
    public enum Kind { UNAVAILABLE, NOT_FOUND, NOT_READY, NO_POSITION }
    private final Kind kind;
    public BrokerException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public Kind kind() { return kind; }
}
