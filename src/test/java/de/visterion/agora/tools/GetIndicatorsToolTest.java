package de.visterion.agora.tools;

import de.visterion.agora.data.*;
import de.visterion.agora.research.IndicatorService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GetIndicatorsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private List<OhlcBar> rising(int n) {
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal c = new BigDecimal(100 + i);
            bars.add(new OhlcBar(LocalDate.parse("2025-01-01").plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000L));
        }
        return bars;
    }

    private MarketDataService svcWith(List<OhlcBar> bars) {
        MarketDataProvider p = new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) { return new Quote(s, BigDecimal.TEN, BigDecimal.ZERO, "USD"); }
            public List<OhlcBar> ohlc(String s, int d) { return bars; }
        };
        return new MarketDataService(List.of(p), 120L);
    }

    private GetIndicatorsTool tool(List<OhlcBar> bars) {
        // atrPeriod=3, atrMultiple=3.0, maFast=2, maSlow=4, minBars52w=5
        IndicatorService ind = new IndicatorService(new IndicatorService.Params(
                3, new BigDecimal("3.0"), 2, 4, 5));
        return new GetIndicatorsTool(svcWith(bars), ind, 260);
    }

    @Test void returnsFullBundleWhenEnoughBars() {
        var r = tool(rising(10)).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        var o = r.output();
        assertThat(o.get("symbol").asString()).isEqualTo("AAPL");
        // rising(10): TR=2 each → ATR(3)=2 exactly (same as GetAtrToolTest)
        assertThat(o.get("atr").decimalValue()).isEqualByComparingTo("2");
        assertThat(o.get("atrAvailable").asBoolean()).isTrue();
        assertThat(o.has("chandelierStop")).isTrue();
        assertThat(o.get("chandelierBreached").isBoolean()).isTrue();
        assertThat(o.get("maFast").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(o.get("maSlow").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(o.get("maCrossState").asString()).isIn("DEATH_CROSS", "BULLISH", "NEUTRAL");
        assertThat(o.get("high52w").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(o.get("low52w").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(o.get("currentClose").decimalValue()).isEqualByComparingTo("109");
        assertThat(o.get("available").asBoolean()).isTrue();
    }

    @Test void shortHistoryStillAvailableWithFalseSubFlags() {
        // 2 bars, atrPeriod=3 → atrAvailable=false, but currentClose present → available:true
        var r = tool(rising(2)).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("atrAvailable").asBoolean()).isFalse();
        assertThat(r.output().get("currentClose").decimalValue()).isEqualByComparingTo("101");
    }

    @Test void paramOverrideApplied() {
        var args = mapper.createObjectNode().put("symbol", "AAPL").put("period", 2);
        var r = tool(rising(5)).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("atrAvailable").asBoolean()).isTrue(); // period=2 needs fewer bars
    }

    @Test void missingSymbolUnavailable() {
        assertThat(tool(rising(10)).call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void noBarsUnavailable() {
        var r = tool(List.<OhlcBar>of()).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isFalse();
    }

    @Test void namespaceIsGeneral() {
        assertThat(tool(rising(10)).namespace()).isEqualTo("general");
    }
}
