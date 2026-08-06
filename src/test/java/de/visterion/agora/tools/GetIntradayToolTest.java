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
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test void unknownIntervalIsInvalidArgumentNotOutage() {
        IntradayService svc = Mockito.mock(IntradayService.class);
        var tool = new GetIntradayTool(svc);
        var args = mapper.createObjectNode().put("symbol", "AAPL").put("interval", "5nn");
        var r = tool.call(args);
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("invalid interval");
        verifyNoInteractions(svc);
    }

    @Test void unknownRangeIsInvalidArgumentNotOutage() {
        IntradayService svc = Mockito.mock(IntradayService.class);
        var tool = new GetIntradayTool(svc);
        var args = mapper.createObjectNode().put("symbol", "AAPL").put("range", "3x");
        var r = tool.call(args);
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("invalid range");
        verifyNoInteractions(svc);
    }

    // --- NOT_FOUND vs UNAVAILABLE at the tool boundary -----------------------
    // "no intraday bars for X" is a statement about ONE instrument, not about Yahoo.
    // It must ride out as an AVAILABLE result (adapter: isError=false) carrying
    // available:false + error, not as an error envelope the caller reads as an outage.

    @Test void notFoundIsAnAvailableNoDataPayloadNotAnOutage() {
        IntradayService svc = Mockito.mock(IntradayService.class);
        when(svc.intraday(any(), any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                        "no intraday bars for SYNA", null));
        var tool = new GetIntradayTool(svc);
        var r = tool.call(mapper.createObjectNode().put("symbol", "SYNA"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("SYNA");
        assertThat(r.output().get("available").asBoolean()).isFalse();
        assertThat(r.output().get("error").asString()).isEqualTo("no intraday bars for SYNA");
        assertThat(r.output().get("bars")).isEmpty();
    }

    @Test void unavailableStaysAnErrorEnvelope() {
        IntradayService svc = Mockito.mock(IntradayService.class);
        when(svc.intraday(any(), any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                        "Yahoo intraday unreachable: connect timeout", null));
        var tool = new GetIntradayTool(svc);
        var r = tool.call(mapper.createObjectNode().put("symbol", "SYNA"));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("Yahoo intraday unreachable");
    }

    @Test void successPayloadCarriesAvailableTrue() {
        IntradayService svc = Mockito.mock(IntradayService.class);
        when(svc.intraday(any(), any(), any())).thenReturn(List.of(
                new IntradayBar(Instant.ofEpochSecond(1749600000L),
                        new BigDecimal("11.0"), new BigDecimal("11.4"),
                        new BigDecimal("10.8"), new BigDecimal("11.2"), 250L)));
        var r = new GetIntradayTool(svc).call(mapper.createObjectNode().put("symbol", "SYNA"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("available").asBoolean()).isTrue();
    }

    @Test void namespaceIsGeneral() {
        assertThat(new GetIntradayTool(Mockito.mock(IntradayService.class)).namespace()).isEqualTo("general");
    }
}
