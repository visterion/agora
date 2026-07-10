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

class GetEarningsWindowToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsMarketWideRowsWithSymbol() {
        EarningsService svc = Mockito.mock(EarningsService.class);
        when(svc.earningsWindow(any(), any())).thenReturn(List.of(
                new EarningsEvent("AAPL", LocalDate.parse("2025-05-01"),
                        new BigDecimal("1.4"), new BigDecimal("1.5"), new BigDecimal("7.1"), null, null),
                new EarningsEvent("MSFT", LocalDate.parse("2025-05-02"),
                        new BigDecimal("2.0"), new BigDecimal("2.1"), new BigDecimal("5.0"), null, null)));
        var args = mapper.createObjectNode().put("from", "2025-05-01").put("to", "2025-05-03");
        var r = new GetEarningsWindowTool(svc).call(args);
        assertThat(r.available()).isTrue();
        var earnings = r.output().get("earnings");
        assertThat(earnings).hasSize(2);
        assertThat(earnings.get(0).get("symbol").asString("")).isEqualTo("AAPL");
        assertThat(earnings.get(0).get("epsActual").decimalValue()).isEqualByComparingTo("1.5");
    }

    @Test void limitTruncates() {
        EarningsService svc = Mockito.mock(EarningsService.class);
        when(svc.earningsWindow(any(), any())).thenReturn(List.of(
                new EarningsEvent("A", LocalDate.parse("2025-05-01"), null, null, null, null, null),
                new EarningsEvent("B", LocalDate.parse("2025-05-01"), null, null, null, null, null),
                new EarningsEvent("C", LocalDate.parse("2025-05-01"), null, null, null, null, null)));
        var args = mapper.createObjectNode().put("from", "2025-05-01").put("to", "2025-05-03").put("limit", 2);
        var r = new GetEarningsWindowTool(svc).call(args);
        assertThat(r.output().get("earnings")).hasSize(2);
    }

    @Test void invalidDateUnavailable() {
        var r = new GetEarningsWindowTool(Mockito.mock(EarningsService.class))
                .call(mapper.createObjectNode().put("from", "not-a-date"));
        assertThat(r.available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        EarningsService svc = Mockito.mock(EarningsService.class);
        when(svc.earningsWindow(any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null));
        assertThat(new GetEarningsWindowTool(svc).call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void fromAfterToUnavailable() {
        var r = new GetEarningsWindowTool(Mockito.mock(EarningsService.class))
                .call(mapper.createObjectNode().put("from", "2025-05-10").put("to", "2025-05-01"));
        assertThat(r.available()).isFalse();
    }

    @Test void windowOver366DaysUnavailable() {
        var r = new GetEarningsWindowTool(Mockito.mock(EarningsService.class))
                .call(mapper.createObjectNode().put("from", "2024-01-01").put("to", "2025-06-01"));
        assertThat(r.available()).isFalse();
    }

    @Test void limitClampedTo100() {
        EarningsService svc = Mockito.mock(EarningsService.class);
        List<EarningsEvent> many = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++)
            many.add(new EarningsEvent("S" + i, LocalDate.parse("2025-05-01"), null, null, null, null, null));
        when(svc.earningsWindow(any(), any())).thenReturn(many);
        var args = mapper.createObjectNode().put("from", "2025-05-01").put("to", "2025-05-03").put("limit", 100_000);
        var r = new GetEarningsWindowTool(svc).call(args);
        assertThat(r.output().get("earnings")).hasSize(100);
        assertThat(r.output().get("truncated").asBoolean()).isTrue();
    }

    @Test void nonIntegralLimitUnavailable() {
        EarningsService svc = Mockito.mock(EarningsService.class);
        when(svc.earningsWindow(any(), any())).thenReturn(List.of());
        var args = mapper.createObjectNode().put("from", "2025-05-01").put("to", "2025-05-03").put("limit", 2.5);
        var r = new GetEarningsWindowTool(svc).call(args);
        assertThat(r.available()).isFalse();
    }

    @Test void notFoundQuietWindowReturnsAvailableEmpty() {
        EarningsService svc = Mockito.mock(EarningsService.class);
        when(svc.earningsWindow(any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no earnings", null));
        var r = new GetEarningsWindowTool(svc).call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("earnings")).isNotNull();
        assertThat(r.output().get("earnings")).isEmpty();
        assertThat(r.output().get("note")).isNotNull();
    }
}
