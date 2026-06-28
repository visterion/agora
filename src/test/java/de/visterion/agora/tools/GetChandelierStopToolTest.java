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

class GetChandelierStopToolTest {
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

    private GetChandelierStopTool toolWith(List<OhlcBar> bars) {
        IndicatorService ind = new IndicatorService(new IndicatorService.Params(
                3, new BigDecimal("3.0"), 2, 4, 5));
        return new GetChandelierStopTool(svcWith(bars), ind, 260);
    }

    @Test
    void returnsChandelierWhenEnoughBars() {
        var tool = toolWith(rising(10));
        var r = tool.call(mapper.createObjectNode().put("symbol", "TSLA"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("TSLA");
        assertThat(r.output().get("chandelierStop").decimalValue()).isNotNull();
        // rising series: currentClose(109) well above chandelierStop → not breached
        assertThat(r.output().get("breached").asBoolean()).isFalse();
        assertThat(r.output().get("available").asBoolean()).isTrue();
    }

    @Test
    void unavailableWhenTooFewBars() {
        var tool = toolWith(rising(2));
        var r = tool.call(mapper.createObjectNode().put("symbol", "TSLA"));
        assertThat(r.available()).isFalse();
    }

    @Test
    void unavailableOnMissingSymbol() {
        var tool = toolWith(rising(10));
        var r = tool.call(mapper.createObjectNode());
        assertThat(r.available()).isFalse();
    }

    @Test
    void unavailableOnNonNumericMultiple() {
        var tool = toolWith(rising(10));
        var args = mapper.createObjectNode().put("symbol", "AAPL").put("multiple", "abc");
        var r = tool.call(args);
        assertThat(r.available()).isFalse();
    }

    @Test
    void periodAndMultipleOverridesApplied() {
        var tool = toolWith(rising(10));
        var args = mapper.createObjectNode().put("symbol", "TSLA")
                .put("period", 2)
                .put("multiple", "1.5");
        var r = tool.call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("chandelierStop").decimalValue()).isNotNull();
    }
}
