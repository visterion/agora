package de.visterion.agora.fetch.split;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SplitAdjusterTest {
    private final List<SplitEvent> tenForOne = List.of(
        new SplitEvent(LocalDate.parse("2024-06-10"), BigDecimal.ONE, BigDecimal.TEN));

    @Test void periodBeforeSplit_getsFullFactor() {
        BigDecimal f = SplitAdjuster.cumulativeFactorAfter(LocalDate.parse("2024-01-28"), tenForOne);
        assertThat(f).isEqualByComparingTo("10");
        assertThat(SplitAdjuster.adjust(new BigDecimal("11.93"), f)).isEqualByComparingTo("1.193");
    }

    @Test void periodAfterSplit_factorOne() {
        BigDecimal f = SplitAdjuster.cumulativeFactorAfter(LocalDate.parse("2024-10-27"), tenForOne);
        assertThat(f).isEqualByComparingTo("1");
    }

    @Test void noSplits_factorOne() {
        assertThat(SplitAdjuster.cumulativeFactorAfter(LocalDate.parse("2024-01-28"), List.of()))
            .isEqualByComparingTo("1");
    }
}
