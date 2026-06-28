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

class GetAtrToolTest {
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

    /** Build a stub MarketDataService using the public 2-arg Spring ctor (cross-package safe). */
    private MarketDataService svcWith(List<OhlcBar> bars) {
        MarketDataProvider p = new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) { return new Quote(s, BigDecimal.TEN, BigDecimal.ZERO, "USD"); }
            public List<OhlcBar> ohlc(String s, int d) { return bars; }
        };
        return new MarketDataService(List.of(p), 120L);
    }

    private GetAtrTool toolWith(List<OhlcBar> bars) {
        IndicatorService ind = new IndicatorService(new IndicatorService.Params(
                3, new BigDecimal("3.0"), 2, 4, 5));
        return new GetAtrTool(svcWith(bars), ind, 260);
    }

    @Test
    void returnsAtrWhenEnoughBars() {
        var tool = toolWith(rising(10));
        var r = tool.call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("AAPL");
        // rising(10) with atrPeriod=3: each interior bar has TR=2 (hl=2, hpc=2, lpc=0).
        // ATR = SMA of last 3 TRs = (2+2+2)/3 = 2 exactly.
        assertThat(r.output().get("atr").decimalValue())
                .isEqualByComparingTo(new BigDecimal("2"));
        assertThat(r.output().get("available").asBoolean()).isTrue();
    }

    @Test
    void unavailableWhenTooFewBars() {
        // atrPeriod=3, bars=2 → trValues.size()=1 < 3 → atrAvailable=false
        var tool = toolWith(rising(2));
        var r = tool.call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isFalse();
    }

    @Test
    void unavailableOnMissingSymbol() {
        var tool = toolWith(rising(10));
        var r = tool.call(mapper.createObjectNode());
        assertThat(r.available()).isFalse();
    }

    @Test
    void periodOverrideIsApplied() {
        // override period=2 (less bars needed) — 5 bars: trValues=4 >= 2 → available
        var tool = toolWith(rising(5));
        var args = mapper.createObjectNode().put("symbol", "AAPL").put("period", 2);
        var r = tool.call(args);
        assertThat(r.available()).isTrue();
    }
}
