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
    }
    @Test void legacy11ArgDefaultsTimestampsNull() {
        var o = new Order("id", "ref", "AAPL", "buy", BigDecimal.ONE, "limit", "new",
                "entry", null, null, null);
        assertThat(o.submittedAt()).isNull();
        assertThat(o.filledAt()).isNull();
    }
    @Test void canonical13ArgCarriesTimestamps() {
        var o = new Order("id", "ref", "AAPL", "buy", BigDecimal.ONE, "limit", "filled",
                "entry", BigDecimal.ONE, new BigDecimal("150"), null,
                "2026-07-01T10:00:00Z", "2026-07-01T10:00:05Z");
        assertThat(o.submittedAt()).isEqualTo("2026-07-01T10:00:00Z");
        assertThat(o.filledAt()).isEqualTo("2026-07-01T10:00:05Z");
    }
}
