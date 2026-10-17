package de.visterion.agora.trading;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class ClosedPositionDtoTest {
    @Test void legacy7ArgDefaultsNewFieldsNull() {
        var cp = new ClosedPosition("ISRG", 36313L, new BigDecimal("364.35"),
                new BigDecimal("364.10"), new BigDecimal("3"), new BigDecimal("-0.25"), "sig-1");
        assertThat(cp.openTime()).isNull();
        assertThat(cp.closeTime()).isNull();
        assertThat(cp.openingPositionId()).isNull();
    }
    @Test void canonical10ArgCarriesNewFields() {
        var cp = new ClosedPosition("ISRG", 36313L, new BigDecimal("364.35"),
                new BigDecimal("364.10"), new BigDecimal("3"), new BigDecimal("-0.25"), "sig-1",
                "2026-07-01T09:00:00Z", "2026-07-01T15:30:00Z", 998877L);
        assertThat(cp.openTime()).isEqualTo("2026-07-01T09:00:00Z");
        assertThat(cp.closeTime()).isEqualTo("2026-07-01T15:30:00Z");
        assertThat(cp.openingPositionId()).isEqualTo(998877L);
    }

    @Test void legacy10ArgDefaultsCostFieldsNull() {
        var cp = new ClosedPosition("ISRG", 36313L, new BigDecimal("364.35"),
                new BigDecimal("364.10"), new BigDecimal("3"), new BigDecimal("-0.25"), "sig-1",
                "2026-07-01T09:00:00Z", "2026-07-01T15:30:00Z", 998877L);
        assertThat(cp.costOpening()).isNull();
        assertThat(cp.costClosing()).isNull();
        assertThat(cp.costOpeningBase()).isNull();
        assertThat(cp.costClosingBase()).isNull();
        assertThat(cp.profitLossCurrencyConversion()).isNull();
    }

    @Test void canonical15ArgCarriesCostFields() {
        var cp = new ClosedPosition("ISRG", 36313L, new BigDecimal("364.35"),
                new BigDecimal("364.10"), new BigDecimal("3"), new BigDecimal("-0.25"), "sig-1",
                "2026-07-01T09:00:00Z", "2026-07-01T15:30:00Z", 998877L,
                new BigDecimal("1.5"), new BigDecimal("1.2"), new BigDecimal("1.35"),
                new BigDecimal("1.08"), new BigDecimal("0.03"));
        assertThat(cp.costOpening()).isEqualByComparingTo("1.5");
        assertThat(cp.costClosing()).isEqualByComparingTo("1.2");
        assertThat(cp.costOpeningBase()).isEqualByComparingTo("1.35");
        assertThat(cp.costClosingBase()).isEqualByComparingTo("1.08");
        assertThat(cp.profitLossCurrencyConversion()).isEqualByComparingTo("0.03");
    }
}
