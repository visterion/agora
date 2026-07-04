package de.visterion.agora.tools;

import de.visterion.agora.data.IntradayBar;
import de.visterion.agora.data.IntradayService;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GetIntradayToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsBars() {
        IntradayService svc = Mockito.mock(IntradayService.class);
        when(svc.intraday(any(), any(), any())).thenReturn(List.of(
                new IntradayBar(Instant.ofEpochSecond(1749600000L),
                        new BigDecimal("10.0"), new BigDecimal("10.2"),
                        new BigDecimal("9.9"), new BigDecimal("10.1"), 100L)));
        var tool = new GetIntradayTool(svc);
        var r = tool.call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("AAPL");
        assertThat(r.output().get("bars").get(0).get("close").decimalValue()).isEqualByComparingTo("10.1");
    }

    @Test void missingSymbolUnavailable() {
        var tool = new GetIntradayTool(Mockito.mock(IntradayService.class));
        assertThat(tool.call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        IntradayService svc = Mockito.mock(IntradayService.class);
        when(svc.intraday(any(), any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null));
        var tool = new GetIntradayTool(svc);
        assertThat(tool.call(mapper.createObjectNode().put("symbol", "AAPL")).available()).isFalse();
    }

    @Test void namespaceIsGeneral() {
        assertThat(new GetIntradayTool(Mockito.mock(IntradayService.class)).namespace()).isEqualTo("general");
    }
}
