package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.finnhub.Fundamentals;
import de.visterion.agora.research.fundamentals.GlobalMetricsRouter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GetFundamentalsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsMetrics() {
        GlobalMetricsRouter router = Mockito.mock(GlobalMetricsRouter.class);
        var metrics = mapper.createObjectNode().put("peTTM", 28.5);
        when(router.fundamentals(any())).thenReturn(new Fundamentals("AAPL", metrics));
        var r = new GetFundamentalsTool(router).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("metrics").get("peTTM").asDouble()).isEqualTo(28.5);
    }

    @Test void missingSymbolUnavailable() {
        assertThat(new GetFundamentalsTool(Mockito.mock(GlobalMetricsRouter.class))
                .call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        GlobalMetricsRouter router = Mockito.mock(GlobalMetricsRouter.class);
        when(router.fundamentals(any())).thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no key", null));
        assertThat(new GetFundamentalsTool(router).call(mapper.createObjectNode().put("symbol", "AAPL")).available()).isFalse();
    }
}
