package de.visterion.agora.trading;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class OrderDtoTest {
    @Test void legacy7ArgDefaultsNewFieldsNull() {
        var o = new Order("id", "ref", "AAPL", "buy", BigDecimal.ONE, "limit", "new");
        assertThat(o.submittedAt()).isNull();
        assertThat(o.filledAt()).isNull();
        assertThat(o.role()).isEqualTo("other");
        assertThat(o.limitPrice()).isNull();
        assertThat(o.stopPrice()).isNull();
    }
    @Test void legacy11ArgDefaultsTimestampsNull() {
        var o = new Order("id", "ref", "AAPL", "buy", BigDecimal.ONE, "limit", "new",
                "entry", null, null, null);
        assertThat(o.submittedAt()).isNull();
        assertThat(o.filledAt()).isNull();
        assertThat(o.limitPrice()).isNull();
        assertThat(o.stopPrice()).isNull();
    }
    @Test void legacy13ArgDefaultsLimitAndStopPriceNull() {
        var o = new Order("id", "ref", "AAPL", "buy", BigDecimal.ONE, "limit", "filled",
                "entry", BigDecimal.ONE, new BigDecimal("150"), null,
                "2026-07-01T10:00:00Z", "2026-07-01T10:00:05Z");
        assertThat(o.submittedAt()).isEqualTo("2026-07-01T10:00:00Z");
        assertThat(o.filledAt()).isEqualTo("2026-07-01T10:00:05Z");
        assertThat(o.limitPrice()).isNull();
        assertThat(o.stopPrice()).isNull();
    }
    @Test void canonical15ArgCarriesLimitAndStopPrice() {
        var o = new Order("id", "ref", "AAPL", "buy", BigDecimal.ONE, "limit", "working",
                "stop_loss", null, null, new BigDecimal("182.53"), new BigDecimal("168.03"), "parent-1",
                "2026-07-01T10:00:00Z", null);
        assertThat(o.limitPrice()).isEqualByComparingTo("182.53");
        assertThat(o.stopPrice()).isEqualByComparingTo("168.03");
        assertThat(o.parentId()).isEqualTo("parent-1");
    }
}
