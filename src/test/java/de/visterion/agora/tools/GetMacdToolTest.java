package de.visterion.agora.tools;

import de.visterion.agora.data.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GetMacdToolTest {
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

    private GetMacdTool tool(List<OhlcBar> bars) {
        return new GetMacdTool(svcWith(bars), 12, 26, 9, 260);
    }

    @Test void risingClosesGiveHistogramConsistency() {
        var r = tool(rising(60)).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("AAPL");
        BigDecimal macd = r.output().get("macd").decimalValue();
        BigDecimal signal = r.output().get("signal").decimalValue();
        BigDecimal histogram = r.output().get("histogram").decimalValue();
        assertThat(histogram).isEqualByComparingTo(macd.subtract(signal));
        // Directional wiring check: on a strictly rising series the fast EMA leads the slow
        // EMA (MACD > 0) and MACD stays above its signal line (histogram > 0). A mis-wired
        // indicator (swapped fast/slow, wrong signal source) would break these.
        assertThat(r.output().get("macd").decimalValue()).isGreaterThan(java.math.BigDecimal.ZERO);
        assertThat(r.output().get("histogram").decimalValue()).isGreaterThan(java.math.BigDecimal.ZERO);
    }

    @Test void tooFewBarsUnavailable() {
        var r = tool(rising(10)).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isFalse();
    }

    @Test void missingSymbolUnavailable() {
        assertThat(tool(rising(60)).call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void namespaceIsGeneral() {
        assertThat(tool(rising(60)).namespace()).isEqualTo("general");
    }
}
