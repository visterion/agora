package de.visterion.agora.tools;

import de.visterion.agora.data.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GetStochasticToolTest {
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

    private GetStochasticTool tool(List<OhlcBar> bars) {
        return new GetStochasticTool(svcWith(bars), 14, 3, 260);
    }

    @Test void risingClosesKInRange() {
        var r = tool(rising(30)).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("AAPL");
        BigDecimal k = r.output().get("k").decimalValue();
        assertThat(k.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
        assertThat(k.compareTo(new BigDecimal("100"))).isLessThanOrEqualTo(0);
        // %D (SMA of %K) must be wired and bounded; while %K climbs toward 100 on a rising
        // series, its SMA lags, so %D <= %K (equality allowed if both saturate at 100).
        assertThat(r.output().get("d").decimalValue()).isBetween(new java.math.BigDecimal("0"), new java.math.BigDecimal("100"));
        assertThat(r.output().get("d").decimalValue()).isLessThanOrEqualTo(r.output().get("k").decimalValue());
    }

    @Test void tooFewBarsUnavailable() {
        var r = tool(rising(5)).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isFalse();
    }

    @Test void missingSymbolUnavailable() {
        assertThat(tool(rising(30)).call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void namespaceIsGeneral() {
        assertThat(tool(rising(30)).namespace()).isEqualTo("general");
    }
}
