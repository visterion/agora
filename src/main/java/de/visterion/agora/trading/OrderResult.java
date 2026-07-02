package de.visterion.agora.trading;

/** accepted=true → brokerOrderId/status set; accepted=false → rejectReason/rejectCode set. */
public record OrderResult(boolean accepted, String brokerOrderId, String clientRef, String status,
                          String rejectReason, String rejectCode) {
    public static OrderResult accepted(String brokerOrderId, String clientRef, String status) {
        return new OrderResult(true, brokerOrderId, clientRef, status, null, null);
    }
    public static OrderResult rejected(String reason, String code) {
        return new OrderResult(false, null, null, null, reason, code);
    }
}
