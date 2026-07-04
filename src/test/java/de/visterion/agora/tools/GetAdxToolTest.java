package de.visterion.agora.tools;

import de.visterion.agora.data.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GetAdxToolTest {
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

    private GetAdxTool tool(List<OhlcBar> bars) {
        return new GetAdxTool(svcWith(bars), 14, 260);
    }

    @Test void risingClosesAdxInRange() {
        var r = tool(rising(40)).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("AAPL");
        BigDecimal adx = r.output().get("adx").decimalValue();
        assertThat(adx.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
        assertThat(adx.compareTo(new BigDecimal("100"))).isLessThanOrEqualTo(0);
    }

    @Test void tooFewBarsUnavailable() {
        var r = tool(rising(10)).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isFalse();
    }

    @Test void missingSymbolUnavailable() {
        assertThat(tool(rising(40)).call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void namespaceIsGeneral() {
        assertThat(tool(rising(40)).namespace()).isEqualTo("general");
    }
}
