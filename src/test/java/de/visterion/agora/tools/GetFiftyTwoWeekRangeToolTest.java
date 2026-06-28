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

class GetFiftyTwoWeekRangeToolTest {
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

    private GetFiftyTwoWeekRangeTool toolWith(List<OhlcBar> bars) {
        // minBarsFor52w=5 (small for tests)
        IndicatorService ind = new IndicatorService(new IndicatorService.Params(
                3, new BigDecimal("3.0"), 2, 4, 5));
        return new GetFiftyTwoWeekRangeTool(svcWith(bars), ind, 260);
    }

    @Test
    void returnsRangeWhenEnoughBars() {
        // 10 bars >= minBarsFor52w=5 → available
        // bar[i]: open=100+i, high=101+i, low=99+i, close=100+i
        // overall high = bars[9].high = 110; overall low = bars[0].low = 99
        var tool = toolWith(rising(10));
        var r = tool.call(mapper.createObjectNode().put("symbol", "GOOG"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("GOOG");
        assertThat(r.output().get("high").decimalValue())
                .isEqualByComparingTo(new BigDecimal("110"));
        assertThat(r.output().get("low").decimalValue())
                .isEqualByComparingTo(new BigDecimal("99"));
        assertThat(r.output().get("available").asBoolean()).isTrue();
    }

    @Test
    void unavailableWhenTooFewBars() {
        // 3 bars < minBarsFor52w=5 → window52wAvailable=false
        var tool = toolWith(rising(3));
        var r = tool.call(mapper.createObjectNode().put("symbol", "GOOG"));
        assertThat(r.available()).isFalse();
    }

    @Test
    void unavailableOnMissingSymbol() {
        var tool = toolWith(rising(10));
        var r = tool.call(mapper.createObjectNode());
        assertThat(r.available()).isFalse();
    }
}
