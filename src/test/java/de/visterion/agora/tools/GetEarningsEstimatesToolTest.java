package de.visterion.agora.tools;

import de.visterion.agora.fetch.finnhub.EarningsEstimate;
import de.visterion.agora.fetch.finnhub.EarningsEstimatesService;
import de.visterion.agora.tool.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetEarningsEstimatesToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void mapsRows() {
        EarningsEstimatesService svc = mock(EarningsEstimatesService.class);
        when(svc.earnings("AAPL")).thenReturn(List.of(
            new EarningsEstimate("2026-03-31", new BigDecimal("1.5"), new BigDecimal("1.4"),
                new BigDecimal("0.1"), new BigDecimal("7.14"))));
        var tool = new GetEarningsEstimatesTool(svc);
        ObjectNode args = mapper.createObjectNode(); args.put("symbol", "AAPL");
        ToolResult r = tool.call(args);
        var row = r.output().path("earnings").get(0);
        assertThat(row.path("period").asString()).isEqualTo("2026-03-31");
        assertThat(row.path("surprise").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(tool.name()).isEqualTo("get_earnings_estimates");
    }

    @Test void noSymbol_unavailable() {
        var tool = new GetEarningsEstimatesTool(mock(EarningsEstimatesService.class));
        assertThat(tool.call(mapper.createObjectNode()).available()).isFalse();
    }
}
