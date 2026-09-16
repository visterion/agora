package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataProvider;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.data.Quote;
import de.visterion.agora.research.BuiltinIndicators;
import de.visterion.agora.research.IndicatorRegistry;
import de.visterion.agora.research.YamlIndicatorCatalog;
import de.visterion.agora.research.ExchangeSessions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    /** 2026-09-16 is a Wednesday; 06:00 UTC is 14:00 in Hong Kong, inside 09:30..16:20. */
    private static final String HK_IN_SESSION = "2026-09-16T06:00:00Z";
    /** 09:00 UTC is 17:00 in Hong Kong — the HK session is over. */
    private static final String HK_AFTER_CLOSE = "2026-09-16T09:00:00Z";

    private static ExchangeSessions sessionsAt(String instant) {
        return new ExchangeSessions(20, 120,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    /** The pre-existing helper: a fixed clock so no test depends on when the suite runs. Every
     *  fixture in the old tests is dated 2025, so the guard never fires for them. */
    private GetIndicatorsTool tool(List<OhlcBar> bars) {
        return tool(bars, sessionsAt(HK_IN_SESSION));
    }

    private GetIndicatorsTool tool(List<OhlcBar> bars, ExchangeSessions sessions) {
        return new GetIndicatorsTool(svcWith(bars), registry(), sessions,
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
    }

    // --- NOT_FOUND vs UNAVAILABLE at the tool boundary -----------------------
    // "this symbol has no history" is a data statement, and get_indicators_batch has always
    // answered it with an available:false entry. The single-symbol tool now agrees, instead of
    // raising an error envelope the caller reads as an Agora outage.

    private GetIndicatorsTool toolNotFound() {
        MarketDataProvider p = new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) { throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no " + s, null); }
            public List<OhlcBar> ohlc(String s, int d) {
                throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no ohlc for " + s, null);
            }
        };
        return new GetIndicatorsTool(new MarketDataService(List.of(p), 120L), registry(),
                sessionsAt(HK_IN_SESSION), List.of("atr"), 260);
    }

    @Test
    void notFoundIsAnAvailableNoDataPayloadNotAnOutage() {
        var r = toolNotFound().call(mapper.createObjectNode().put("symbol", "SYNA"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("symbol").asString()).isEqualTo("SYNA");
        assertThat(r.output().get("available").asBoolean()).isFalse();
        assertThat(r.output().get("error").asString()).contains("SYNA");
        assertThat(r.output().has("values")).isFalse();
    }

    @Test
    void emptyBarsIsTheSameNoDataStatement() {
        var r = tool(List.of()).call(mapper.createObjectNode().put("symbol", "SYNA"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("available").asBoolean()).isFalse();
        assertThat(r.output().get("error").asString()).isEqualTo("no data for SYNA");
    }

    @Test
    void unavailableStaysAnErrorEnvelope() {
        // tool(null) makes the stub provider throw UNAVAILABLE ("stub down").
        var r = tool(null).call(mapper.createObjectNode().put("symbol", "SYNA"));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("stub down");
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
                sessionsAt(HK_IN_SESSION),
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

    // -------------------------------------------------------------------------
    // SP8: values are computed over completed bars only; the in-progress bar is reported
    // separately. Fixture arithmetic (hand-invented, no captured data):
    //   bars 0..28: close = 100+i, high = close+1, low = close-1, open = close
    //               -> every true range is max(2, 2, 0) = 2
    //   bar 29    : o 129, h 134, l 123, c 130 -> true range max(11, 6, 5) = 11
    //   atr(22) over all 30 bars      = (21*2 + 11)/22 = 53/22 = 2.4091
    //   atr(22) over the 29 completed = 22*2/22        = 2
    //   sma(2)  over all 30 bars      = (128 + 130)/2  = 129
    //   sma(2)  over the 29 completed = (127 + 128)/2  = 127.5
    // -------------------------------------------------------------------------

    private static OhlcBar bar(String date, String o, String h, String l, String c) {
        return new OhlcBar(LocalDate.parse(date), new BigDecimal(o), new BigDecimal(h),
                new BigDecimal(l), new BigDecimal(c), 1000L);
    }

    /** The 29 completed bars, oldest first, ending the day before {@code lastDate}. */
    private static List<OhlcBar> completedBars(String lastDate) {
        List<OhlcBar> bars = new ArrayList<>();
        LocalDate last = LocalDate.parse(lastDate);
        for (int i = 0; i < 29; i++) {
            BigDecimal c = new BigDecimal(100 + i);
            bars.add(new OhlcBar(last.minusDays(29 - i), c, c.add(BigDecimal.ONE),
                    c.subtract(BigDecimal.ONE), c, 1000L));
        }
        return bars;
    }

    /** The 29 completed bars plus the wide in-progress bar dated {@code lastDate}. */
    private static List<OhlcBar> thirtyBarsEndingOn(String lastDate) {
        List<OhlcBar> bars = new ArrayList<>(completedBars(lastDate));
        bars.add(bar(lastDate, "129", "134", "123", "130"));
        return bars;
    }

    /** atr + a 2-period sma: one recomputes to a round number, the other to a fraction. */
    private ObjectNode atrAndSmaArgs(String symbol) {
        ObjectNode args = mapper.createObjectNode().put("symbol", symbol);
        var specs = args.putArray("indicators");
        specs.add("atr");
        ObjectNode sma = specs.addObject();
        sma.put("name", "sma");
        sma.putObject("params").put("period", 2);
        return args;
    }

    /** (1) In session: the newest bar is dropped from the values and reported on its own. */
    @Test void inSessionTheValuesUseTheCompletedBarsOnly() {
        var r = tool(thirtyBarsEndingOn("2026-09-16"), sessionsAt(HK_IN_SESSION))
                .call(atrAndSmaArgs("ACME.HK"));

        assertThat(r.available()).isTrue();
        JsonNode out = r.output();
        assertThat(out.get("partialBar").asBoolean()).isTrue();
        assertThat(out.get("asOf").asString()).isEqualTo("2026-09-15");
        assertThat(out.get("lastCompletedClose").decimalValue()).isEqualByComparingTo("128");
        assertThat(out.get("currentClose").decimalValue()).isEqualByComparingTo("130");
        assertThat(out.get("currentHigh").decimalValue()).isEqualByComparingTo("134");
        assertThat(out.get("currentLow").decimalValue()).isEqualByComparingTo("123");
        assertThat(out.get("sessionZone").asString()).isEqualTo("Asia/Hong_Kong");
        // 2, not the 2.4091 the in-progress bar's true range of 11 would produce.
        assertThat(value(out, "atr").get("value").decimalValue()).isEqualByComparingTo("2");
        assertThat(value(out, "sma").get("value").decimalValue()).isEqualByComparingTo("127.5");
    }

    /** (2) After the close nothing is dropped — this passes on a no-op and pins the boundary. */
    @Test void afterTheCloseTheNewestBarCounts() {
        var r = tool(thirtyBarsEndingOn("2026-09-16"), sessionsAt(HK_AFTER_CLOSE))
                .call(atrAndSmaArgs("ACME.HK"));

        JsonNode out = r.output();
        assertThat(out.get("partialBar").asBoolean()).isFalse();
        assertThat(out.get("asOf").asString()).isEqualTo("2026-09-16");
        assertThat(out.get("lastCompletedClose").decimalValue()).isEqualByComparingTo("130");
        assertThat(out.get("currentClose").decimalValue()).isEqualByComparingTo("130");
        assertThat(out.get("sessionZone").asString()).isEqualTo("Asia/Hong_Kong");
        assertThat(value(out, "atr").get("value").decimalValue()).isEqualByComparingTo("2.4091");
        assertThat(value(out, "sma").get("value").decimalValue()).isEqualByComparingTo("129");
    }

    /** (3) Two rows for the same in-progress date: de-duplication runs first, last row wins,
     *  and exactly one bar is dropped. */
    @Test void duplicateRowsForTheInProgressDateAreDedupedBeforeTheGuard() {
        List<OhlcBar> bars = new ArrayList<>(thirtyBarsEndingOn("2026-09-16"));
        bars.add(bar("2026-09-16", "130", "136", "122", "131"));   // later row for the same date

        var r = tool(bars, sessionsAt(HK_IN_SESSION)).call(atrAndSmaArgs("ACME.HK"));

        JsonNode out = r.output();
        assertThat(out.get("partialBar").asBoolean()).isTrue();
        assertThat(out.get("asOf").asString()).isEqualTo("2026-09-15");
        assertThat(out.get("lastCompletedClose").decimalValue()).isEqualByComparingTo("128");
        assertThat(out.get("currentClose").decimalValue()).isEqualByComparingTo("131");
        assertThat(out.get("currentHigh").decimalValue()).isEqualByComparingTo("136");
        assertThat(out.get("currentLow").decimalValue()).isEqualByComparingTo("122");
        assertThat(value(out, "atr").get("value").decimalValue()).isEqualByComparingTo("2");
        assertThat(value(out, "sma").get("value").decimalValue()).isEqualByComparingTo("127.5");
    }

    /** (4) An out-of-order provider list: the guard judges the latest DATE, not the last row.
     *  Reading the raw last element instead would see 2026-09-15, call nothing partial, and
     *  report currentClose 128. */
    @Test void outOfOrderBarsAreSortedBeforeTheGuardRuns() {
        List<OhlcBar> completed = completedBars("2026-09-16");
        List<OhlcBar> bars = new ArrayList<>(completed.subList(0, 28));       // ... 2026-09-14
        bars.add(bar("2026-09-16", "129", "134", "123", "130"));              // today
        bars.add(completed.get(28));                                          // 2026-09-15, last

        var r = tool(bars, sessionsAt(HK_IN_SESSION)).call(atrAndSmaArgs("ACME.HK"));

        JsonNode out = r.output();
        assertThat(out.get("partialBar").asBoolean()).isTrue();
        assertThat(out.get("asOf").asString()).isEqualTo("2026-09-15");
        assertThat(out.get("lastCompletedClose").decimalValue()).isEqualByComparingTo("128");
        assertThat(out.get("currentClose").decimalValue()).isEqualByComparingTo("130");
        assertThat(value(out, "atr").get("value").decimalValue()).isEqualByComparingTo("2");
        assertThat(value(out, "sma").get("value").decimalValue()).isEqualByComparingTo("127.5");
    }

    /** (5) Nothing but an in-progress bar is a data statement, in the established shape. */
    @Test void aSeriesOfOnlyAnInProgressBarIsUnavailable() {
        var bars = List.of(bar("2026-09-16", "129", "134", "123", "130"));

        var r = tool(bars, sessionsAt(HK_IN_SESSION)).call(atrAndSmaArgs("ACME.HK"));

        assertThat(r.available()).isTrue();
        JsonNode out = r.output();
        assertThat(out.get("available").asBoolean()).isFalse();
        assertThat(out.get("error").asString())
                .isEqualTo("only an in-progress bar for ACME.HK");
        assertThat(out.has("values")).isFalse();
        assertThat(out.has("partialBar")).isFalse();
    }
}
