package de.visterion.agora.tools;

import de.visterion.agora.data.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GetOhlcToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    // Uses the public 2-arg ctor (ttlSeconds) — cross-package tests can't reach the package-private
    // 3-arg test ctor. That's fine: these tests exercise tool output shape, not cache timing.
    private MarketDataService svcWith(MarketDataProvider p) { return new MarketDataService(List.of(p), 120L); }

    private MarketDataProvider okProvider() {
        return new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "n/a", null); }
            public List<OhlcBar> ohlc(String s, int d) {
                return List.of(new OhlcBar(LocalDate.of(2024, 1, 2),
                        new BigDecimal("185.00"), new BigDecimal("187.50"),
                        new BigDecimal("184.00"), new BigDecimal("186.75"), 55_123_456L));
            }
        };
    }

    @Test
    void returnsBarsForSymbol() {
        var tool = new GetOhlcTool(svcWith(okProvider()));
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.put("days", 5);
        var r = tool.call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("AAPL");
        assertThat(r.output().get("bars").get(0).get("close").decimalValue())
                .isEqualByComparingTo("186.75");
        assertThat(r.output().get("bars").get(0).get("volume").asLong()).isEqualTo(55_123_456L);
    }

    @Test
    void unavailableWhenNoSymbol() {
        var tool = new GetOhlcTool(svcWith(okProvider()));
        var r = tool.call(mapper.createObjectNode());
        assertThat(r.available()).isFalse();
        assertThat(r.error()).isNotBlank();
    }

    @Test
    void unavailableWhenProviderFails() {
        MarketDataProvider failing = new MarketDataProvider() {
            public String name() { return "x"; }
            public Quote quote(String s) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null); }
            public List<OhlcBar> ohlc(String s, int d) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null); }
        };
        var tool = new GetOhlcTool(svcWith(failing));
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        var r = tool.call(args);
        assertThat(r.available()).isFalse();
        assertThat(r.error()).isNotBlank();
    }

    @Test
    void defaultDaysUsedWhenNotProvided() {
        var tool = new GetOhlcTool(svcWith(okProvider()));
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        var r = tool.call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("bars")).hasSize(1);
    }

    @Test
    void oversizedDaysIsClampedTo1825() {
        int[] seen = new int[1];
        MarketDataProvider capturing = new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "n/a", null); }
            public List<OhlcBar> ohlc(String s, int d) {
                seen[0] = d;
                return List.of(new OhlcBar(LocalDate.of(2024, 1, 2),
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L));
            }
        };
        var tool = new GetOhlcTool(svcWith(capturing));
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.put("days", 1_000_000);
        assertThat(tool.call(args).available()).isTrue();
        assertThat(seen[0]).isEqualTo(1825);
    }

    @Test
    void nonPositiveDaysIsClampedTo1() {
        int[] seen = new int[1];
        MarketDataProvider capturing = new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "n/a", null); }
            public List<OhlcBar> ohlc(String s, int d) {
                seen[0] = d;
                return List.of(new OhlcBar(LocalDate.of(2024, 1, 2),
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L));
            }
        };
        var tool = new GetOhlcTool(svcWith(capturing));
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.put("days", -5);
        assertThat(tool.call(args).available()).isTrue();
        assertThat(seen[0]).isEqualTo(1);
    }
}
