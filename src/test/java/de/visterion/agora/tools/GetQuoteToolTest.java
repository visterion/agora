package de.visterion.agora.tools;

import de.visterion.agora.data.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GetQuoteToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private MarketDataService svcWith(MarketDataProvider p) { return new MarketDataService(List.of(p), 120L); }

    private MarketDataProvider okProvider() {
        return new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) { return new Quote(s, new BigDecimal("201.34"), new BigDecimal("0.83"), "USD"); }
            public List<OhlcBar> ohlc(String s, int d) { return List.of(); }
        };
    }

    @Test
    void returnsQuotesArray() {
        var tool = new GetQuoteTool(svcWith(okProvider()));
        ObjectNode args = mapper.createObjectNode();
        args.putArray("symbols").add("AAPL");
        var r = tool.call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("quotes").get(0).get("symbol").asString()).isEqualTo("AAPL");
        assertThat(r.output().get("quotes").get(0).get("price").decimalValue()).isEqualByComparingTo("201.34");
    }

    @Test
    void acceptsSingleSymbolField() {
        var tool = new GetQuoteTool(svcWith(okProvider()));
        ObjectNode args = mapper.createObjectNode().put("symbol", "MSFT");
        var r = tool.call(args);
        assertThat(r.output().get("quotes").get(0).get("symbol").asString()).isEqualTo("MSFT");
    }

    @Test
    void unavailableWhenAllFail() {
        MarketDataProvider failing = new MarketDataProvider() {
            public String name() { return "x"; }
            public Quote quote(String s) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null); }
            public List<OhlcBar> ohlc(String s, int d) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null); }
        };
        var tool = new GetQuoteTool(svcWith(failing));
        ObjectNode args = mapper.createObjectNode();
        args.putArray("symbols").add("AAPL");
        var r = tool.call(args);
        assertThat(r.available()).isFalse();
        assertThat(r.error()).isNotBlank();
    }
}
