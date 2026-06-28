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

class GetMaCrossToolTest {
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

    private GetMaCrossTool toolWith(List<OhlcBar> bars) {
        // Params: atrPeriod=3, atrMultiple=3.0, maFast=2, maSlow=4, minBarsFor52w=5
        IndicatorService ind = new IndicatorService(new IndicatorService.Params(
                3, new BigDecimal("3.0"), 2, 4, 5));
        return new GetMaCrossTool(svcWith(bars), ind, 260);
    }

    @Test
    void returnsMaCrossWhenBothMasAvailable() {
        // 10 bars: maFast=2 → need ≥2 bars; maSlow=4 → need ≥4 bars; both available
        var tool = toolWith(rising(10));
        var r = tool.call(mapper.createObjectNode().put("symbol", "MSFT"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("MSFT");
        assertThat(r.output().get("maFast").decimalValue()).isNotNull();
        assertThat(r.output().get("maSlow").decimalValue()).isNotNull();
        // Rising series: last 2 bars avg > last 4 bars avg → BULLISH
        assertThat(r.output().get("crossState").asString()).isEqualTo("BULLISH");
        assertThat(r.output().get("available").asBoolean()).isTrue();
    }

    @Test
    void unavailableWhenTooFewBarsForSlowMa() {
        // 3 bars: maFast=2 available, maSlow=4 NOT available → unavailable
        var tool = toolWith(rising(3));
        var r = tool.call(mapper.createObjectNode().put("symbol", "MSFT"));
        assertThat(r.available()).isFalse();
    }

    @Test
    void unavailableOnMissingSymbol() {
        var tool = toolWith(rising(10));
        var r = tool.call(mapper.createObjectNode());
        assertThat(r.available()).isFalse();
    }

    @Test
    void fastAndSlowOverridesApplied() {
        // override to fast=2, slow=3 (both fit in 5 bars, and slow > fast)
        var tool = toolWith(rising(5));
        var args = mapper.createObjectNode().put("symbol", "MSFT")
                .put("fast", 2)
                .put("slow", 3);
        var r = tool.call(args);
        assertThat(r.available()).isTrue();
    }
}
