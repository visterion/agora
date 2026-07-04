package de.visterion.agora.tools;

import de.visterion.agora.data.FxRate;
import de.visterion.agora.data.FxService;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GetFxRateToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsRate() {
        FxService svc = Mockito.mock(FxService.class);
        when(svc.rate(any(), any())).thenReturn(new FxRate("EUR", "USD", new BigDecimal("1.0842")));
        var tool = new GetFxRateTool(svc);
        var r = tool.call(mapper.createObjectNode().put("from", "EUR").put("to", "USD"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("rate").decimalValue()).isEqualByComparingTo("1.0842");
        assertThat(r.output().get("from").asString()).isEqualTo("EUR");
    }

    @Test void missingArgsUnavailable() {
        var tool = new GetFxRateTool(Mockito.mock(FxService.class));
        assertThat(tool.call(mapper.createObjectNode().put("from", "EUR")).available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        FxService svc = Mockito.mock(FxService.class);
        when(svc.rate(any(), any())).thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null));
        var tool = new GetFxRateTool(svc);
        assertThat(tool.call(mapper.createObjectNode().put("from", "EUR").put("to", "USD")).available()).isFalse();
    }
}
