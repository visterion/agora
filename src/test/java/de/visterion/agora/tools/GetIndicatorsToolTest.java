package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataProvider;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.data.Quote;
import de.visterion.agora.research.BuiltinIndicators;
import de.visterion.agora.research.IndicatorRegistry;
import de.visterion.agora.research.YamlIndicatorCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GetIndicatorsToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** N bars, close == 100+i (rising), high=close+1, low=close-1. */
    private List<OhlcBar> rising(int n) {
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal c = new BigDecimal(100 + i);
            bars.add(new OhlcBar(LocalDate.parse("2025-01-01").plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000L));
        }
        return bars;
    }

    /** N bars, close == i+1 — for hand-computed SMA cases. */
    private List<OhlcBar> counting(int n) {
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal c = new BigDecimal(i + 1);
            bars.add(new OhlcBar(LocalDate.parse("2025-01-01").plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000L));
        }
        return bars;
    }

    private static IndicatorRegistry registry() {
        var reg = new IndicatorRegistry();
        BuiltinIndicators.defs().forEach(reg::register);
        try (InputStream in = GetIndicatorsToolTest.class
                .getResourceAsStream("/indicators-catalog.yaml")) {
            YamlIndicatorCatalog.load(in).forEach(reg::register);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return reg;
    }

    private MarketDataService svcWith(List<OhlcBar> bars) {
        MarketDataProvider p = new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) { return new Quote(s, BigDecimal.TEN, BigDecimal.ZERO, "USD"); }
            public List<OhlcBar> ohlc(String s, int d) {
                if (bars == null) throw new MarketDataException(
                        MarketDataException.Kind.UNAVAILABLE, "stub down", null);
                return bars;
            }
        };
        return new MarketDataService(List.of(p), 120L);
    }

    private GetIndicatorsTool tool(List<OhlcBar> bars) {
        return new GetIndicatorsTool(svcWith(bars), registry(),
                List.of("atr", "chandelier_stop", "ma_cross", "52w_range"), 260);
    }

    private static JsonNode value(JsonNode out, String label) {
        for (JsonNode e : out.get("values")) {
            if (label.equals(e.path("label").asString())) return e;
        }
        return null;
    }

    @Test
    void stringShorthandComputesRsi() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("indicators").add("rsi");
        // rsi(14) is now warmup:recursive -> minBars = 1+4*14 = 57; rising(30) is no longer enough.
        var r = tool(rising(60)).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("AAPL");
        assertThat(r.output().get("currentClose").decimalValue()).isEqualByComparingTo("159");
        assertThat(r.output().get("asOf").asString()).isEqualTo("2025-03-01");
        JsonNode rsi = value(r.output(), "rsi");
        assertThat(rsi.get("available").asBoolean()).isTrue();
        assertThat(rsi.get("value").decimalValue()).isEqualByComparingTo("100");
        assertThat(rsi.has("series")).isFalse();
    }

    @Test
    void defaultPaletteWhenIndicatorsOmitted() {
        var r = tool(rising(300)).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("values")).hasSize(4);
        assertThat(value(r.output(), "atr").get("available").asBoolean()).isTrue();
        assertThat(value(r.output(), "chandelier_stop").get("available").asBoolean()).isTrue();
        JsonNode ma = value(r.output(), "ma_cross");
        assertThat(ma.get("available").asBoolean()).isTrue();
        assertThat(ma.get("value").get("fast").decimalValue())
                .isGreaterThan(ma.get("value").get("slow").decimalValue());
        // research low (a): windowed to the last 252 bars, not the full 300-bar history.
        // rising(300): close(i)=100+i, high=close+1, low=close-1. Last 252 bars: i=48..299.
        JsonNode range = value(r.output(), "52w_range");
        assertThat(range.get("value").get("high").decimalValue()).isEqualByComparingTo("400");
        assertThat(range.get("value").get("low").decimalValue()).isEqualByComparingTo("147");
    }

    @Test
    void compositionAndSeries() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "X").put("series", 3);
        ObjectNode spec = args.putArray("indicators").addObject();
        spec.put("name", "sma");
        spec.putObject("params").put("period", 2);
        var r = tool(counting(5)).call(args);
        JsonNode sma = value(r.output(), "sma");
        assertThat(sma.get("value").decimalValue()).isEqualByComparingTo("4.5");
        assertThat(sma.get("series")).hasSize(3);
        assertThat(sma.get("series").get(0).decimalValue()).isEqualByComparingTo("2.5");
        assertThat(sma.get("series").get(1).decimalValue()).isEqualByComparingTo("3.5");
        assertThat(sma.get("series").get(2).decimalValue()).isEqualByComparingTo("4.5");
    }

    @Test
    void perSpecErrorDoesNotKillTheCall() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("indicators").add("rsi").add("bogus");
        var r = tool(rising(60)).call(args);
        assertThat(r.available()).isTrue();
        assertThat(value(r.output(), "rsi").get("available").asBoolean()).isTrue();
        JsonNode values = r.output().get("values");
        JsonNode broken = values.get(1);
        assertThat(broken.get("available").asBoolean()).isFalse();
        assertThat(broken.get("error").asString()).contains("unknown indicator 'bogus'");
    }

    @Test
    void duplicateLabelIsPerSpecError() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("indicators").add("rsi").add("rsi");
        var r = tool(rising(60)).call(args);
        assertThat(r.output().get("values").get(0).get("available").asBoolean()).isTrue();
        JsonNode second = r.output().get("values").get(1);
        assertThat(second.get("available").asBoolean()).isFalse();
        assertThat(second.get("error").asString()).contains("duplicate label");
    }

    @Test
    void insufficientHistoryIsPerSpecUnavailable() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("indicators").add("rsi");
        var r = tool(rising(5)).call(args);
        assertThat(r.available()).isTrue();
        JsonNode rsi = value(r.output(), "rsi");
        assertThat(rsi.get("available").asBoolean()).isFalse();
        assertThat(rsi.get("error").asString()).contains("insufficient history");
    }

    @Test
    void callLevelErrors() {
        assertThat(tool(rising(30)).call(mapper.createObjectNode()).available()).isFalse();

        ObjectNode tooMany = mapper.createObjectNode().put("symbol", "A");
        var arr = tooMany.putArray("indicators");
        for (int i = 0; i < 21; i++) arr.add("rsi");
        var r = tool(rising(30)).call(tooMany);
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("max 20");

        ObjectNode badSeries = mapper.createObjectNode().put("symbol", "A").put("series", 999);
        assertThat(tool(rising(30)).call(badSeries).available()).isFalse();

        assertThat(tool(null).call(mapper.createObjectNode().put("symbol", "A")).available()).isFalse();

        assertThat(tool(List.of()).call(mapper.createObjectNode().put("symbol", "A")).available()).isFalse();
    }

    @Test
    void nonNumericSeriesIsCallLevelUnavailable() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "A").put("series", "abc");
        var r = tool(rising(30)).call(args);
        assertThat(r.available()).isFalse();
    }

    @Test
    void namespaceIsGeneral() {
        assertThat(tool(rising(30)).namespace()).isEqualTo("general");
    }

    // -------------------------------------------------------------------------
    // H3: rsi(14) must not report garbage as "available" right at the old minBars (15).
    // -------------------------------------------------------------------------

    @Test
    void rsiUnavailableAtOldMinBarsFifteen() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("indicators").add("rsi");
        var r = tool(rising(15)).call(args);
        JsonNode rsi = value(r.output(), "rsi");
        assertThat(rsi.get("available").asBoolean()).isFalse();
        assertThat(rsi.get("error").asString()).contains("insufficient history");
    }

    @Test
    void rsiAvailableAndNonDegenerateAtConvergenceSafeMinBars() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("indicators").add("rsi");
        // mixed (not strictly monotonic) series so RSI isn't trivially pinned at 0/100
        var bars = mixed(60);
        var r = tool(bars).call(args);
        JsonNode rsi = value(r.output(), "rsi");
        assertThat(rsi.get("available").asBoolean()).isTrue();
        BigDecimal v = rsi.get("value").decimalValue();
        assertThat(v).isGreaterThan(BigDecimal.ZERO).isLessThan(new BigDecimal("100"));
    }

    // -------------------------------------------------------------------------
    // H3 follow-up (Task 8 review finding 1): macd's signal line is an EMAIndicator
    // (recursive filter) -> must not report garbage as "available" at the old exact
    // minBars (slow+signal=35).
    // -------------------------------------------------------------------------

    @Test
    void macdUnavailableAtOldMinBarsThirtyFive() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("indicators").add("macd");
        var r = tool(rising(35)).call(args);
        JsonNode macd = value(r.output(), "macd");
        assertThat(macd.get("available").asBoolean()).isFalse();
        assertThat(macd.get("error").asString()).contains("insufficient history");
    }

    @Test
    void macdAvailableAndNonDegenerateAtConvergenceSafeMinBars() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("indicators").add("macd");
        // convergence-safe minBars for macd(12,26,9) = 1 + 4*(26+9) = 141
        var bars = mixed(150);
        var r = tool(bars).call(args);
        JsonNode macd = value(r.output(), "macd");
        assertThat(macd.get("available").asBoolean()).isTrue();
        // macd has multiple outputs (macd/signal/histogram) -> "value" is an object
        assertThat(macd.get("value").get("macd").decimalValue().doubleValue()).isFinite();
    }

    /** N bars with an up/down zig-zag so RSI reflects mixed gains and losses. */
    private List<OhlcBar> mixed(int n) {
        List<OhlcBar> bars = new ArrayList<>();
        BigDecimal px = new BigDecimal("100");
        for (int i = 0; i < n; i++) {
            px = (i % 3 == 0) ? px.subtract(new BigDecimal("1.5")) : px.add(BigDecimal.ONE);
            bars.add(new OhlcBar(LocalDate.parse("2025-01-01").plusDays(i),
                    px, px.add(BigDecimal.ONE), px.subtract(BigDecimal.ONE), px, 1000L));
        }
        return bars;
    }

    // -------------------------------------------------------------------------
    // H4: series output must not be NaN-poisoned by sequential stateful evaluation.
    // -------------------------------------------------------------------------

    @Test
    void seriesOfSmaOfRsiHasNoNaNPoisoning() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "X").put("series", 250);
        ObjectNode spec = args.putArray("indicators").addObject();
        spec.put("name", "sma");
        spec.putObject("params").put("period", 5);
        spec.set("of", mapper.createObjectNode().put("name", "rsi"));
        var r = tool(mixed(300)).call(args);
        JsonNode sma = value(r.output(), "sma(rsi)");
        assertThat(sma.get("available").asBoolean()).isTrue();
        JsonNode series = sma.get("series");
        assertThat(series.size()).isGreaterThan(0);
        for (JsonNode v : series) {
            assertThat(v.isNull()).as("series point must not be null/NaN-poisoned").isFalse();
        }
    }

    @Test
    void seriesStartsAtFirstStableIndexNoWarmupGarbage() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "X").put("series", 250);
        ObjectNode spec = args.putArray("indicators").addObject();
        spec.put("name", "sma");
        spec.putObject("params").put("period", 2);
        var res = tool(counting(30)).call(args);
        JsonNode sma = value(res.output(), "sma");
        // sma(period=2) minBars = 3 (1+period) -> series length = 30 - (3-1) = 28
        assertThat(sma.get("series").size()).isEqualTo(28);
        assertThat(sma.get("series").get(0).decimalValue()).isEqualByComparingTo("2.5");
    }

    // -------------------------------------------------------------------------
    // M-X4: fetchDays is clamped to 1825, never forwarded unbounded.
    // -------------------------------------------------------------------------

    @Test
    void fetchDaysIsClampedTo1825() {
        AtomicInteger capturedDays = new AtomicInteger(-1);
        MarketDataProvider p = new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) { return new Quote(s, BigDecimal.TEN, BigDecimal.ZERO, "USD"); }
            public List<OhlcBar> ohlc(String s, int d) {
                capturedDays.set(d);
                return rising(30);
            }
        };
        var tool = new GetIndicatorsTool(new MarketDataService(List.of(p), 120L), registry(),
                List.of("atr", "chandelier_stop", "ma_cross", "52w_range"), 260);
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL").put("fetchDays", 2_000_000);
        args.putArray("indicators").add("rsi");
        tool.call(args);
        assertThat(capturedDays.get()).isEqualTo(1825);
    }

    // -------------------------------------------------------------------------
    // Research low (g): top-level 'available' is true only if >=1 spec is available.
    // -------------------------------------------------------------------------

    @Test
    void topLevelAvailableIsFalseWhenAllSpecsFail() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("indicators").add("rsi");
        var r = tool(rising(5)).call(args); // too short for rsi -> per-spec unavailable
        assertThat(r.output().get("available").asBoolean()).isFalse();
    }

    @Test
    void topLevelAvailableIsTrueWhenAtLeastOneSpecSucceeds() {
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("indicators").add("rsi").add("bogus");
        var r = tool(mixed(60)).call(args);
        assertThat(r.output().get("available").asBoolean()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Research low (e): NaN at a stable index is a distinct "math domain error", not
    // "insufficient history" (bar count already satisfied minBars).
    // -------------------------------------------------------------------------

    @Test
    void naNAtStableIndexIsReportedAsMathDomainError() {
        // williams_r on a perfectly flat series: highest-lowest window = 0 -> division by
        // zero -> NaN, even though minBars (15) is comfortably satisfied by 60 bars.
        List<OhlcBar> flat = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            BigDecimal c = new BigDecimal("100.00");
            flat.add(new OhlcBar(LocalDate.parse("2025-01-01").plusDays(i), c, c, c, c, 1000L));
        }
        ObjectNode args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("indicators").add("williams_r");
        var r = tool(flat).call(args);
        JsonNode wr = value(r.output(), "williams_r");
        assertThat(wr.get("available").asBoolean()).isFalse();
        assertThat(wr.get("error").asString()).contains("math domain error");
        assertThat(wr.get("error").asString()).doesNotContain("insufficient history");
    }
}
