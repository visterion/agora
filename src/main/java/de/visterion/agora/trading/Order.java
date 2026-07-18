package de.visterion.agora.trading;

import java.math.BigDecimal;

/**
 * A neutral order DTO. {@code role} distinguishes a bracket's entry leg from its
 * stop-loss/take-profit legs ({@code "entry"|"stop_loss"|"take_profit"|"other"}); {@code
 * parentId} points a leg back at its bracket parent's {@code brokerOrderId} (null for
 * top-level orders). {@code filledQty}/{@code avgFillPrice} are nullable — populated when
 * the broker's response exposes them, null otherwise. {@code submittedAt}/{@code filledAt}
 * are nullable ISO-8601 timestamps — populated when the broker's response exposes them.
 */
public record Order(String brokerOrderId, String clientRef, String symbol, String side,
                    BigDecimal qty, String type, String status,
                    String role, BigDecimal filledQty, BigDecimal avgFillPrice, String parentId,
                    String submittedAt, String filledAt) {

    /** Legacy 7-arg shape: role defaults to "other", fill/parent/timestamp fields null. */
    public Order(String brokerOrderId, String clientRef, String symbol, String side,
                 BigDecimal qty, String type, String status) {
        this(brokerOrderId, clientRef, symbol, side, qty, type, status, "other", null, null, null, null, null);
    }

    /** 11-arg shape (role + fills + parentId, no timestamps). */
    public Order(String brokerOrderId, String clientRef, String symbol, String side,
                 BigDecimal qty, String type, String status,
                 String role, BigDecimal filledQty, BigDecimal avgFillPrice, String parentId) {
        this(brokerOrderId, clientRef, symbol, side, qty, type, status, role, filledQty, avgFillPrice, parentId, null, null);
    }
}
