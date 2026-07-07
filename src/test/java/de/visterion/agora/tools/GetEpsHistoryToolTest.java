package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.EpsPoint;
import de.visterion.agora.fetch.finnhub.SplitService;
import de.visterion.agora.fetch.split.SplitEvent;
import de.visterion.agora.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class GetEpsHistoryToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private EpsPoint pt(String end, String val) {
        return new EpsPoint(LocalDate.parse(end), null, new BigDecimal(val), 2024, "FY", "10-K", null);
    }

    @Test void returnsEps() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        SplitService splits = Mockito.mock(SplitService.class);
        when(svc.epsHistory(any(), any())).thenReturn(List.of(
                new EpsPoint(LocalDate.parse("2025-03-31"), LocalDate.parse("2025-01-01"),
                        new BigDecimal("2.40"), 2025, "Q1", "10-Q", LocalDate.parse("2025-05-01"))));
        var r = new GetEpsHistoryTool(svc, splits).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("eps").get(0).get("value").decimalValue()).isEqualByComparingTo("2.40");
    }

    @Test void missingSymbolAndCikUnavailable() {
        assertThat(new GetEpsHistoryTool(Mockito.mock(EdgarService.class), Mockito.mock(SplitService.class))
                .call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void notFoundUnavailable() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        SplitService splits = Mockito.mock(SplitService.class);
        when(svc.epsHistory(any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no CIK", null));
        assertThat(new GetEpsHistoryTool(svc, splits).call(mapper.createObjectNode().put("symbol", "ZZZZ")).available()).isFalse();
    }

    @Test void adjusted_dividesPreSplitEps() {
        EdgarService edgar = mock(EdgarService.class);
        SplitService splits = mock(SplitService.class);
        when(edgar.epsHistory("NVDA", null)).thenReturn(List.of(pt("2024-01-28", "11.93")));
        when(splits.splits("NVDA")).thenReturn(List.of(
            new SplitEvent(LocalDate.parse("2024-06-10"), BigDecimal.ONE, BigDecimal.TEN)));
        var tool = new GetEpsHistoryTool(edgar, splits);
        ObjectNode args = mapper.createObjectNode(); args.put("symbol", "NVDA"); args.put("adjusted", true);
        ToolResult r = tool.call(args);
        assertThat(r.output().path("adjusted").asBoolean()).isTrue();
        var row = r.output().path("eps").get(0);
        assertThat(row.path("value").decimalValue()).isEqualByComparingTo("1.193");
        assertThat(row.path("adjustmentFactor").decimalValue()).isEqualByComparingTo("10");
    }

    @Test void adjusted_noSplits_fallsBackToReported() {
        EdgarService edgar = mock(EdgarService.class);
        SplitService splits = mock(SplitService.class);
        when(edgar.epsHistory("AAPL", null)).thenReturn(List.of(pt("2024-01-28", "6.13")));
        when(splits.splits("AAPL")).thenReturn(List.of());
        var tool = new GetEpsHistoryTool(edgar, splits);
        ObjectNode args = mapper.createObjectNode(); args.put("symbol", "AAPL"); args.put("adjusted", true);
        ToolResult r = tool.call(args);
        assertThat(r.output().path("adjusted").asBoolean()).isFalse();
        assertThat(r.output().path("eps").get(0).path("value").decimalValue()).isEqualByComparingTo("6.13");
    }

    @Test void adjusted_splitFetchThrows_fallsBackToReported() {
        EdgarService edgar = mock(EdgarService.class);
        SplitService splits = mock(SplitService.class);
        when(edgar.epsHistory("AAPL", null)).thenReturn(List.of(pt("2024-01-28", "6.13")));
        when(splits.splits("AAPL")).thenThrow(new RuntimeException("boom"));
        var tool = new GetEpsHistoryTool(edgar, splits);
        ObjectNode args = mapper.createObjectNode(); args.put("symbol", "AAPL"); args.put("adjusted", true);
        ToolResult r = tool.call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().path("adjusted").asBoolean()).isFalse();
        assertThat(r.output().path("eps").get(0).path("value").decimalValue()).isEqualByComparingTo("6.13");
    }

    @Test void default_isAsReported() {
        EdgarService edgar = mock(EdgarService.class);
        SplitService splits = mock(SplitService.class);
        when(edgar.epsHistory("AAPL", null)).thenReturn(List.of(pt("2024-01-28", "6.13")));
        var tool = new GetEpsHistoryTool(edgar, splits);
        ObjectNode args = mapper.createObjectNode(); args.put("symbol", "AAPL");
        ToolResult r = tool.call(args);
        assertThat(r.output().path("eps").get(0).path("value").decimalValue()).isEqualByComparingTo("6.13");
        verifyNoInteractions(splits);
    }
}
