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

class GetRFrameworkToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private List<OhlcBar> flatClose(int n, String close) {
        // high/low band ±1 so ATR is well-defined; close constant so currentClose is known
        List<OhlcBar> bars = new ArrayList<>();
        BigDecimal c = new BigDecimal(close);
        for (int i = 0; i < n; i++)
            bars.add(new OhlcBar(LocalDate.parse("2025-01-01").plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000L));
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

    private GetRFrameworkTool tool(List<OhlcBar> bars) {
        IndicatorService ind = new IndicatorService(new IndicatorService.Params(
                3, new BigDecimal("3.0"), 2, 4, 5));
        return new GetRFrameworkTool(svcWith(bars), ind, new BigDecimal("3.0"),
                List.of(BigDecimal.ONE, new BigDecimal("2")), 260);
    }

    @Test void explicitStopComputesRiskAndTargets() {
        // price=100 (flat), stopLevel=90 → risk=10, targets: 1R=110, 2R=120
        var args = mapper.createObjectNode().put("symbol", "AAPL").put("stopLevel", "90");
        var r = tool(flatClose(10, "100")).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("price").decimalValue()).isEqualByComparingTo("100");
        assertThat(r.output().get("stopLevel").decimalValue()).isEqualByComparingTo("90");
        assertThat(r.output().get("riskPerUnit").decimalValue()).isEqualByComparingTo("10");
        var targets = r.output().get("targets");
        assertThat(targets.get(0).get("rMultiple").decimalValue()).isEqualByComparingTo("1");
        assertThat(targets.get(0).get("level").decimalValue()).isEqualByComparingTo("110");
        assertThat(targets.get(1).get("level").decimalValue()).isEqualByComparingTo("120");
    }

    @Test void derivesStopFromAtrWhenAbsent() {
        // flat close=100, high/low ±1 → TR=2 each, ATR(3)=2 → stop = 100 - 3*2 = 94, risk=6
        var r = tool(flatClose(10, "100")).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("stopLevel").decimalValue()).isEqualByComparingTo("94");
        assertThat(r.output().get("riskPerUnit").decimalValue()).isEqualByComparingTo("6");
        assertThat(r.output().get("targets").get(0).get("level").decimalValue()).isEqualByComparingTo("106");
    }

    @Test void missingSymbolUnavailable() {
        assertThat(tool(flatClose(10, "100")).call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void noStopAndNoAtrUnavailable() {
        // only 2 bars, atrPeriod=3 → ATR unavailable, no explicit stop → unavailable
        var r = tool(flatClose(2, "100")).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isFalse();
    }
}
