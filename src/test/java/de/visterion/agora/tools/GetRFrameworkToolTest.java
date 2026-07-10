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

    private List<OhlcBar> flatCloseWithAtr(int n, String close, String halfBand) {
        // high/low band ±halfBand so TR = 2*halfBand each bar → ATR = 2*halfBand
        List<OhlcBar> bars = new ArrayList<>();
        BigDecimal c = new BigDecimal(close);
        BigDecimal h = new BigDecimal(halfBand);
        for (int i = 0; i < n; i++)
            bars.add(new OhlcBar(LocalDate.parse("2025-01-01").plusDays(i),
                    c, c.add(h), c.subtract(h), c, 1000L));
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

    private GetRFrameworkTool toolWithMultiples(List<OhlcBar> bars, List<BigDecimal> multiples) {
        IndicatorService ind = new IndicatorService(new IndicatorService.Params(
                3, new BigDecimal("3.0"), 2, 4, 5));
        return new GetRFrameworkTool(svcWith(bars), ind, new BigDecimal("3.0"), multiples, 260);
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

    @Test void short_derivesStopAbovePrice_andDescendingTargets() {
        // price=100, atr=10, atrMultiple=3 → stop=130, risk=30, targets: 70, 40, 10
        var bars = flatCloseWithAtr(10, "100", "5");
        var t = toolWithMultiples(bars, List.of(BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("3")));
        var args = mapper.createObjectNode();
        args.put("symbol", "X");
        args.put("direction", "short");
        var r = t.call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().path("direction").asString()).isEqualTo("short");
        assertThat(r.output().path("stopLevel").decimalValue()).isEqualByComparingTo("130");
        assertThat(r.output().path("riskPerUnit").decimalValue()).isEqualByComparingTo("30");
        assertThat(r.output().path("targets").get(0).path("level").decimalValue()).isEqualByComparingTo("70");
        assertThat(r.output().path("targets").get(1).path("level").decimalValue()).isEqualByComparingTo("40");
        assertThat(r.output().path("targets").get(2).path("level").decimalValue()).isEqualByComparingTo("10");
    }

    @Test void short_explicitStopBelowPrice_isRejected() {
        var bars = flatCloseWithAtr(10, "100", "5");
        var args = mapper.createObjectNode();
        args.put("symbol", "X");
        args.put("direction", "short");
        args.put("stopLevel", 90); // must be ABOVE price for short
        var r = tool(bars).call(args);
        assertThat(r.available()).isFalse();
    }

    @Test void invalidDirectionUnavailable() {
        var args = mapper.createObjectNode();
        args.put("symbol", "X");
        args.put("direction", "up");
        var r = tool(flatClose(10, "100")).call(args);
        assertThat(r.available()).isFalse();
    }

    @Test void nonPositiveAtrMultipleUnavailable() {
        var args = mapper.createObjectNode();
        args.put("symbol", "X");
        args.put("atrMultiple", 0);
        var r = tool(flatClose(10, "100")).call(args);
        assertThat(r.available()).isFalse();
    }

    @Test void explicitEmptyRMultiplesUnavailable() {
        var args = mapper.createObjectNode();
        args.put("symbol", "X");
        args.put("stopLevel", "90");
        args.putArray("rMultiples");
        var r = tool(flatClose(10, "100")).call(args);
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("non-empty");
    }

    @Test void nonPositiveRMultipleEntryUnavailable() {
        var args = mapper.createObjectNode();
        args.put("symbol", "X");
        args.put("stopLevel", "90");
        var rm = args.putArray("rMultiples");
        rm.add(1);
        rm.add(0);
        var r = tool(flatClose(10, "100")).call(args);
        assertThat(r.available()).isFalse();
    }

    @Test void long_withoutDirection_unchanged() {
        // price=100, atr=10, atrMultiple=3 → stop=70, targets 130,160,190
        var bars = flatCloseWithAtr(10, "100", "5");
        var t = toolWithMultiples(bars, List.of(BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("3")));
        var args = mapper.createObjectNode();
        args.put("symbol", "X");
        var r = t.call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().path("stopLevel").decimalValue()).isEqualByComparingTo("70");
        assertThat(r.output().path("direction").asString()).isEqualTo("long");
        assertThat(r.output().path("targets").get(0).path("level").decimalValue()).isEqualByComparingTo("130");
        assertThat(r.output().path("targets").get(1).path("level").decimalValue()).isEqualByComparingTo("160");
        assertThat(r.output().path("targets").get(2).path("level").decimalValue()).isEqualByComparingTo("190");
    }
}
