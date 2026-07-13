package de.visterion.agora.data;

import java.math.BigDecimal;

public record MinorUnitCurrency(String currency, BigDecimal divisor) {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    public static MinorUnitCurrency of(String raw) {
        return switch (raw) {
            case "GBp", "GBX" -> new MinorUnitCurrency("GBP", HUNDRED);
            case "ZAc" -> new MinorUnitCurrency("ZAR", HUNDRED);
            default -> new MinorUnitCurrency(raw, BigDecimal.ONE);
        };
    }
    public BigDecimal apply(BigDecimal v) { return divisor.compareTo(BigDecimal.ONE) == 0 ? v : v.divide(divisor); }
}
