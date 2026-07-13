package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MinorUnitCurrencyTest {
    @Test void normalisesPenceToPounds() {
        MinorUnitCurrency n = MinorUnitCurrency.of("GBp");
        assertThat(n.currency()).isEqualTo("GBP");
        assertThat(n.apply(new BigDecimal("7500"))).isEqualByComparingTo("75");
    }
    @Test void passesThroughMajorUnits() {
        assertThat(MinorUnitCurrency.of("EUR").apply(new BigDecimal("100")))
                .isEqualByComparingTo("100");
    }
}
