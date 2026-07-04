package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.EpsPoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GetEpsHistoryToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsEps() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.epsHistory(any(), any())).thenReturn(List.of(
                new EpsPoint(LocalDate.parse("2025-03-31"), LocalDate.parse("2025-01-01"),
                        new BigDecimal("2.40"), 2025, "Q1", "10-Q", LocalDate.parse("2025-05-01"))));
        var r = new GetEpsHistoryTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("eps").get(0).get("value").decimalValue()).isEqualByComparingTo("2.40");
    }

    @Test void missingSymbolAndCikUnavailable() {
        assertThat(new GetEpsHistoryTool(Mockito.mock(EdgarService.class))
                .call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void notFoundUnavailable() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.epsHistory(any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no CIK", null));
        assertThat(new GetEpsHistoryTool(svc).call(mapper.createObjectNode().put("symbol", "ZZZZ")).available()).isFalse();
    }
}
