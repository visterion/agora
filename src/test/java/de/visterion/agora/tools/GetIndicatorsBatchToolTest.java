package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataProvider;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.data.Quote;
import de.visterion.agora.research.BuiltinIndicators;
import de.visterion.agora.research.ExchangeSessions;
import de.visterion.agora.research.IndicatorRegistry;
import de.visterion.agora.research.YamlIndicatorCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** get_indicators_batch. Synthetic symbols and prices only. */
class GetIndicatorsBatchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** N bars, close == 100+i (rising), high=close+1, low=close-1. */
    private static List<OhlcBar> rising(int n) {
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal c = new BigDecimal(100 + i);
            bars.add(new OhlcBar(LocalDate.parse("2025-01-01").plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000L));
        }
        return bars;
    }

    private static IndicatorRegistry registry() {
        var reg = new IndicatorRegistry();
        BuiltinIndicators.defs().forEach(reg::register);
        try (InputStream in = GetIndicatorsBatchToolTest.class
                .getResourceAsStream("/indicators-catalog.yaml")) {
            YamlIndicatorCatalog.load(in).forEach(reg::register);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return reg;
    }

    private static MarketDataService svcServing(Map<String, List<OhlcBar>> served) {
        MarketDataProvider p = new MarketDataProvider() {
            public String name() { return "stub-batch"; }
            public Quote quote(String s) { return new Quote(s, BigDecimal.TEN, BigDecimal.ZERO, "USD"); }
            public List<OhlcBar> ohlc(String s, int d) {
                List<OhlcBar> bars = served.get(s);
                if (bars == null) throw new MarketDataException(
                        MarketDataException.Kind.NOT_FOUND, "no " + s, null);
                return bars;
            }
            public boolean supportsOhlcBatch() { return true; }
            public Map<String, List<OhlcBar>> ohlcBatch(List<String> symbols, int days) {
                Map<String, List<OhlcBar>> out = new LinkedHashMap<>();
                for (String s : symbols) if (served.containsKey(s)) out.put(s, served.get(s));
                return out;
            }
        };
        return new MarketDataService(List.of(p), 120L);
    }

    /** 2026-09-16 is a Wednesday; 06:00 UTC is 14:00 in Hong Kong (session running) and
     *  02:00 in New York (session not yet open). One clock, two different verdicts. */
    private static final String HK_IN_SESSION_US_CLOSED = "2026-09-16T06:00:00Z";

    private static ExchangeSessions sessionsAt(String instant) {
        return new ExchangeSessions(20, 120,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static GetIndicatorsBatchTool tool(Map<String, List<OhlcBar>> served) {
        return tool(served, sessionsAt(HK_IN_SESSION_US_CLOSED));
    }

    private static GetIndicatorsBatchTool tool(Map<String, List<OhlcBar>> served,
                                               ExchangeSessions sessions) {
        return new GetIndicatorsBatchTool(svcServing(served), registry(), sessions,
                List.of("atr", "chandelier_stop", "ma_cross", "52w_range"), 260);
    }

    private static ObjectNode args(String... symbols) {
        ObjectNode a = new ObjectMapper().createObjectNode();
        ArrayNode arr = a.putArray("symbols");
        for (String s : symbols) arr.add(s);
        return a;
    }

    private static JsonNode result(JsonNode out, String symbol) {
        for (JsonNode e : out.get("results")) {
            if (symbol.equals(e.path("symbol").asString())) return e;
        }
        return null;
    }

    @Test void computesTheSameValuesPerSymbolAsGetIndicators() {
        Map<String, List<OhlcBar>> served = new LinkedHashMap<>();
        served.put("SYNA", rising(300));
        served.put("SYNB", rising(300));
        ObjectNode a = args("SYNA", "SYNB");
        a.putArray("indicators").add("rsi");

        var sessions = sessionsAt(HK_IN_SESSION_US_CLOSED);
        var batch = tool(served, sessions).call(a);
        var single = new GetIndicatorsTool(svcServing(served), registry(), sessions,
                List.of("rsi"), 260).call(mapper.createObjectNode().put("symbol", "SYNA"));

        assertThat(batch.available()).isTrue();
        assertThat(result(batch.output(), "SYNA")).isEqualTo(single.output());
        assertThat(batch.output().get("requested").asInt()).isEqualTo(2);
        assertThat(batch.output().get("returned").asInt()).isEqualTo(2);
    }

    @Test void symbolWithoutBarsIsReportedNotOmitted() {
        Map<String, List<OhlcBar>> served = new LinkedHashMap<>();
        served.put("SYNA", rising(300));
        ObjectNode a = args("SYNA", "SYNGAP");
        a.putArray("indicators").add("rsi");

        var r = tool(served).call(a);

        assertThat(r.output().get("results")).hasSize(2);
        JsonNode gap = result(r.output(), "SYNGAP");
        assertThat(gap).isNotNull();
        assertThat(gap.get("available").asBoolean()).isFalse();
        assertThat(gap.get("error").asString()).contains("SYNGAP");
        assertThat(r.output().get("requested").asInt()).isEqualTo(2);
        assertThat(r.output().get("returned").asInt()).isEqualTo(1);
        assertThat(r.available()).isTrue();
    }

    @Test void availableIsFalseOnlyWhenNoSymbolProducedAValue() {
        ObjectNode a = args("SYNGAP1", "SYNGAP2");
        a.putArray("indicators").add("rsi");

        var r = tool(Map.of()).call(a);

        assertThat(r.available()).isTrue();                    // the tool ran
        assertThat(r.output().get("available").asBoolean()).isFalse();
        assertThat(r.output().get("returned").asInt()).isZero();
        assertThat(r.output().get("results")).hasSize(2);
    }

    /** A symbol with bars but too little history is available=false too — the batch flag only
     *  goes false when NOT ONE symbol produced a value. */
    @Test void aSymbolWithTooLittleHistoryIsFalseWhileTheBatchStaysTrue() {
        Map<String, List<OhlcBar>> served = new LinkedHashMap<>();
        served.put("SYNA", rising(300));
        served.put("SYNSHORT", rising(5));
        ObjectNode a = args("SYNA", "SYNSHORT");
        a.putArray("indicators").add("rsi");                   // rsi(14) needs 57 bars

        var r = tool(served).call(a);

        assertThat(result(r.output(), "SYNSHORT").get("available").asBoolean()).isFalse();
        assertThat(result(r.output(), "SYNA").get("available").asBoolean()).isTrue();
        assertThat(r.output().get("available").asBoolean()).isTrue();
        assertThat(r.output().get("returned").asInt()).isEqualTo(1);
    }

    @Test void overTheSymbolCapTheCallIsRejectedNotTruncated() {
        ObjectNode a = mapper.createObjectNode();
        ArrayNode arr = a.putArray("symbols");
        for (int i = 0; i < 601; i++) arr.add("SYN" + i);

        var r = tool(Map.of()).call(a);

        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("too many symbols").contains("600");
    }

    @Test void exactlyTheCapIsAccepted() {
        ObjectNode a = mapper.createObjectNode();
        ArrayNode arr = a.putArray("symbols");
        for (int i = 0; i < 600; i++) arr.add("SYN" + i);
        a.putArray("indicators").add("rsi");

        var r = tool(Map.of()).call(a);

        assertThat(r.available()).isTrue();
        assertThat(r.output().get("requested").asInt()).isEqualTo(600);
        assertThat(r.output().get("results")).hasSize(600);
    }

    @Test void noSymbolsIsRejected() {
        assertThat(tool(Map.of()).call(mapper.createObjectNode()).error())
                .isEqualTo("no symbols provided");
    }

    @Test void commaSeparatedSymbolsAreAccepted() {
        Map<String, List<OhlcBar>> served = new LinkedHashMap<>();
        served.put("SYNA", rising(300));
        served.put("SYNB", rising(300));
        ObjectNode a = mapper.createObjectNode().put("symbols", "SYNA, SYNB");
        a.putArray("indicators").add("rsi");

        var r = tool(served).call(a);

        assertThat(r.output().get("requested").asInt()).isEqualTo(2);
        assertThat(r.output().get("returned").asInt()).isEqualTo(2);
    }

    @Test void duplicatesAreCountedOnce() {
        Map<String, List<OhlcBar>> served = new LinkedHashMap<>();
        served.put("SYNA", rising(300));
        ObjectNode a = args("SYNA", "SYNA");
        a.putArray("indicators").add("rsi");

        var r = tool(served).call(a);

        assertThat(r.output().get("requested").asInt()).isEqualTo(1);
        assertThat(r.output().get("results")).hasSize(1);
    }

    /** Tickers are case-insensitive upstream: ["SYNA","syna"] is one symbol, not one plus a
     *  phantom "no data" entry. */
    @Test void duplicatesThatDifferOnlyInCaseAreCountedOnce() {
        Map<String, List<OhlcBar>> served = new LinkedHashMap<>();
        served.put("SYNA", rising(300));
        ObjectNode a = args("SYNA", "syna");
        a.putArray("indicators").add("rsi");

        var r = tool(served).call(a);

        assertThat(r.output().get("requested").asInt()).isEqualTo(1);
        assertThat(r.output().get("returned").asInt()).isEqualTo(1);
        assertThat(r.output().get("results")).hasSize(1);
    }

    @Test void indicatorArgumentValidationMatchesTheSingleTool() {
        ObjectNode a = args("SYNA").put("series", -1);
        assertThat(tool(Map.of()).call(a).error()).isEqualTo("series must be 0..250");

        ObjectNode b = args("SYNA");
        b.putArray("indicators");
        assertThat(tool(Map.of()).call(b).error()).isEqualTo("indicators must not be empty");
    }

    // -------------------------------------------------------------------------
    // SP8: the guard is per symbol, from one clock. Fixture arithmetic (hand-invented):
    //   bars 0..28: close = 100+i, high = close+1, low = close-1 -> every true range is 2
    //   bar 29    : o 129, h 134, l 123, c 130                   -> true range 11
    //   atr(22) over all 30 = (21*2 + 11)/22 = 2.4091 ; over the 29 completed = 2
    // -------------------------------------------------------------------------

    private static List<OhlcBar> thirtyBarsEndingOn(String lastDate) {
        List<OhlcBar> bars = new ArrayList<>();
        LocalDate last = LocalDate.parse(lastDate);
        for (int i = 0; i < 29; i++) {
            BigDecimal c = new BigDecimal(100 + i);
            bars.add(new OhlcBar(last.minusDays(29 - i), c, c.add(BigDecimal.ONE),
                    c.subtract(BigDecimal.ONE), c, 1000L));
        }
        bars.add(new OhlcBar(last, new BigDecimal("129"), new BigDecimal("134"),
                new BigDecimal("123"), new BigDecimal("130"), 1000L));
        return bars;
    }

    /** One venue is trading and the other is not, so the two entries must disagree. */
    @Test void theGuardIsDecidedPerSymbol() {
        Map<String, List<OhlcBar>> served = new LinkedHashMap<>();
        served.put("ACME.HK", thirtyBarsEndingOn("2026-09-16"));
        served.put("SYNA", thirtyBarsEndingOn("2026-09-16"));
        ObjectNode a = args("ACME.HK", "SYNA");
        a.putArray("indicators").add("atr");

        var r = tool(served).call(a);

        JsonNode hk = result(r.output(), "ACME.HK");
        assertThat(hk.get("partialBar").asBoolean()).isTrue();
        assertThat(hk.get("asOf").asString()).isEqualTo("2026-09-15");
        assertThat(hk.get("lastCompletedClose").decimalValue()).isEqualByComparingTo("128");
        assertThat(hk.get("currentClose").decimalValue()).isEqualByComparingTo("130");
        assertThat(hk.get("sessionZone").asString()).isEqualTo("Asia/Hong_Kong");
        assertThat(hk.get("values").get(0).get("value").decimalValue()).isEqualByComparingTo("2");

        JsonNode us = result(r.output(), "SYNA");
        assertThat(us.get("partialBar").asBoolean()).isFalse();
        assertThat(us.get("asOf").asString()).isEqualTo("2026-09-16");
        assertThat(us.get("lastCompletedClose").decimalValue()).isEqualByComparingTo("130");
        assertThat(us.get("sessionZone").asString()).isEqualTo("America/New_York");
        assertThat(us.get("values").get(0).get("value").decimalValue())
                .isEqualByComparingTo("2.4091");

        assertThat(r.output().get("returned").asInt()).isEqualTo(2);
    }

    /** A symbol whose only bar is in progress is a reported gap, not a silent omission. */
    @Test void onlyInProgressBarSymbolLowersReturned() {
        Map<String, List<OhlcBar>> served = new LinkedHashMap<>();
        served.put("ACME.HK", List.of(new OhlcBar(LocalDate.parse("2026-09-16"),
                new BigDecimal("129"), new BigDecimal("134"), new BigDecimal("123"),
                new BigDecimal("130"), 1000L)));
        served.put("SYNA", thirtyBarsEndingOn("2026-09-16"));
        ObjectNode a = args("ACME.HK", "SYNA");
        a.putArray("indicators").add("atr");

        var r = tool(served).call(a);

        assertThat(r.output().get("results")).hasSize(2);
        JsonNode hk = result(r.output(), "ACME.HK");
        assertThat(hk.get("available").asBoolean()).isFalse();
        assertThat(hk.get("error").asString()).isEqualTo("only an in-progress bar for ACME.HK");
        assertThat(r.output().get("requested").asInt()).isEqualTo(2);
        assertThat(r.output().get("returned").asInt()).isEqualTo(1);
        assertThat(r.output().get("available").asBoolean()).isTrue();
    }
}
