package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.earnings.EarningsEvent;
import de.visterion.agora.fetch.earnings.EarningsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GetEarningsCalendarToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsEarnings() {
        EarningsService svc = Mockito.mock(EarningsService.class);
        when(svc.earnings(any(), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(
                new EarningsEvent("AAPL", LocalDate.parse("2025-05-01"),
                        new BigDecimal("1.4"), new BigDecimal("1.5"), new BigDecimal("7.1"), null, null)));
        var r = new GetEarningsCalendarTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("earnings").get(0).get("epsActual").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(r.output().get("earnings").get(0).get("date").asString()).isEqualTo("2025-05-01");
    }

    @Test void missingSymbolUnavailable() {
        assertThat(new GetEarningsCalendarTool(Mockito.mock(EarningsService.class))
                .call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void badExplicitDateUnavailable() {
        var r = new GetEarningsCalendarTool(Mockito.mock(EarningsService.class))
                .call(mapper.createObjectNode().put("symbol", "AAPL").put("from", "not-a-date"));
        assertThat(r.available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        EarningsService svc = Mockito.mock(EarningsService.class);
        when(svc.earnings(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null));
        assertThat(new GetEarningsCalendarTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL")).available()).isFalse();
    }
}
