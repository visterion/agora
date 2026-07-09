package de.visterion.agora.trading;

import java.math.BigDecimal;

/**
 * accepted=true → brokerOrderId/status set; accepted=false → rejectReason/rejectCode set.
 *
 * <p>Three optional (nullable) groups piggyback on the same envelope rather than growing
 * separate result types:
 * <ul>
 *   <li>{@code closedQty}/{@code remainingQty}/{@code avgFillPrice} — populated by
 *       {@link BrokerProvider#flatten} for a partial or full close, when the broker's
 *       response makes them available.</li>
 *   <li>{@code stopLegId}/{@code takeProfitLegId} — populated by
 *       {@link BrokerProvider#submitBracket} when the broker's placement response (or a
 *       best-effort follow-up lookup) reveals the child leg order ids.</li>
 * </ul>
 * Any accepted result may have all five as null — that's the "we don't know" case, not
 * an error.</p>
 */
public record OrderResult(boolean accepted, String brokerOrderId, String clientRef, String status,
                          String rejectReason, String rejectCode,
                          BigDecimal closedQty, BigDecimal remainingQty, BigDecimal avgFillPrice,
                          String stopLegId, String takeProfitLegId) {

    public static OrderResult accepted(String brokerOrderId, String clientRef, String status) {
        return new OrderResult(true, brokerOrderId, clientRef, status, null, null,
                null, null, null, null, null);
    }

    /** Accepted flatten (partial or full close) with whatever fill detail the broker exposed. */
    public static OrderResult accepted(String brokerOrderId, String clientRef, String status,
                                        BigDecimal closedQty, BigDecimal remainingQty, BigDecimal avgFillPrice) {
        return new OrderResult(true, brokerOrderId, clientRef, status, null, null,
                closedQty, remainingQty, avgFillPrice, null, null);
    }

    /** Accepted bracket placement with the child leg ids, when known. */
    public static OrderResult accepted(String brokerOrderId, String clientRef, String status,
                                        String stopLegId, String takeProfitLegId) {
        return new OrderResult(true, brokerOrderId, clientRef, status, null, null,
                null, null, null, stopLegId, takeProfitLegId);
    }

    public static OrderResult rejected(String reason, String code) {
        return new OrderResult(false, null, null, null, reason, code,
                null, null, null, null, null);
    }
}
