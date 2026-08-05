package de.visterion.agora.trading;

import java.math.BigDecimal;
import java.util.List;

/**
 * accepted=true → brokerOrderId/status set; accepted=false → rejectReason/rejectCode set.
 *
 * <p>Four optional (nullable) groups piggyback on the same envelope rather than growing
 * separate result types:
 * <ul>
 *   <li>{@code closedQty}/{@code remainingQty}/{@code avgFillPrice} — populated by
 *       {@link BrokerProvider#flatten} for a partial or full close, when the broker's
 *       response makes them available.</li>
 *   <li>{@code stopLegId}/{@code takeProfitLegId} — populated by
 *       {@link BrokerProvider#submitBracket} when the broker's placement response (or a
 *       best-effort follow-up lookup) reveals the child leg order ids.</li>
 *   <li>{@code protectiveLegs}/{@code legsCollapsed} — populated by
 *       {@link BrokerProvider#flatten} when a partial close is rolled back and protective
 *       orders are restored with new broker ids. Rejections that restore legs must carry
 *       these ids or callers' order book will reference cancelled orders.</li>
 * </ul>
 * Any accepted result may have all five as null — that's the "we don't know" case, not
 * an error.</p>
 */
public record OrderResult(boolean accepted, String brokerOrderId, String clientRef, String status,
                          String rejectReason, String rejectCode,
                          BigDecimal closedQty, BigDecimal remainingQty, BigDecimal avgFillPrice,
                          String stopLegId, String takeProfitLegId,
                          List<RestoredLeg> protectiveLegs, boolean legsCollapsed) {

    public static OrderResult accepted(String brokerOrderId, String clientRef, String status) {
        return new OrderResult(true, brokerOrderId, clientRef, status, null, null,
                null, null, null, null, null, List.of(), false);
    }

    /** Accepted flatten (partial or full close) with whatever fill detail the broker exposed. */
    public static OrderResult accepted(String brokerOrderId, String clientRef, String status,
                                        BigDecimal closedQty, BigDecimal remainingQty, BigDecimal avgFillPrice) {
        return new OrderResult(true, brokerOrderId, clientRef, status, null, null,
                closedQty, remainingQty, avgFillPrice, null, null, List.of(), false);
    }

    /** Accepted bracket placement with the child leg ids, when known. */
    public static OrderResult accepted(String brokerOrderId, String clientRef, String status,
                                        String stopLegId, String takeProfitLegId) {
        return new OrderResult(true, brokerOrderId, clientRef, status, null, null,
                null, null, null, stopLegId, takeProfitLegId, List.of(), false);
    }

    /** Accepted partial close that restored protective legs for the remainder. */
    public static OrderResult acceptedWithLegs(String brokerOrderId, String clientRef, String status,
                                               BigDecimal closedQty, BigDecimal remainingQty,
                                               BigDecimal avgFillPrice,
                                               List<RestoredLeg> protectiveLegs, boolean legsCollapsed) {
        return new OrderResult(true, brokerOrderId, clientRef, status, null, null,
                closedQty, remainingQty, avgFillPrice, null, null,
                List.copyOf(protectiveLegs), legsCollapsed);
    }

    /**
     * Rejection that nevertheless changed broker state: the protective legs were put back and
     * carry NEW ids. Callers MUST persist these — the stop ratchet addresses legs by id, so a
     * book left pointing at the cancelled ids fails every later modify with LEG_NOT_FOUND.
     */
    public static OrderResult rejectedWithLegs(String reason, String code,
                                               List<RestoredLeg> protectiveLegs, boolean legsCollapsed) {
        return new OrderResult(false, null, null, null, reason, code,
                null, null, null, null, null,
                List.copyOf(protectiveLegs), legsCollapsed);
    }

    public static OrderResult rejected(String reason, String code) {
        return new OrderResult(false, null, null, null, reason, code,
                null, null, null, null, null, List.of(), false);
    }
}
