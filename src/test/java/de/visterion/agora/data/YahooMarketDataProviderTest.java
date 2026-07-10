package de.visterion.agora.data;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class YahooMarketDataProviderTest {

    static WireMockServer wm;
    YahooMarketDataProvider provider;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        provider = new YahooMarketDataProvider(wm.baseUrl(), "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TestAgent/1.0", 0L);
    }

    // --- quote() tests ---

    @Test
    void quoteReturnsPrice_dayChange_currency() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("range", equalTo("1d"))
                .withQueryParam("interval", equalTo("1d"))
                .withHeader("User-Agent", containing("Windows"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "meta":{
                            "regularMarketPrice":190.5,
                            "chartPreviousClose":188.0,
                            "currency":"USD"
                        },
                        "indicators":{"quote":[{"close":[188.0,190.5]}]}
                    }],"error":null}}
                    """)));

        Quote q = provider.quote("AAPL");

        assertThat(q.symbol()).isEqualTo("AAPL");
        assertThat(q.price()).isEqualByComparingTo("190.5");
        assertThat(q.currency()).isEqualTo("USD");
        // dayChangePercent = (190.5 - 188.0) / 188.0 * 100, MathContext(6,HALF_UP), scale 4 → 1.3298
        assertThat(q.dayChangePercent()).isEqualByComparingTo("1.3298");
    }

    @Test
    void quoteEmptyResultThrowsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/ZZZZ"))
                .willReturn(okJson("""
                    {"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}
                    """)));

        assertThatThrownBy(() -> provider.quote("ZZZZ"))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test
    void quote5xxThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> provider.quote("AAPL"))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test
    void quote429RetriesOnceAndSucceeds() {
        // Use a fast provider (retryBaseMs=0) to avoid actual sleeping
        var fast = new YahooMarketDataProvider(wm.baseUrl(), "TestAgent/1.0", 0L);

        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .inScenario("rl").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("second"));
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .inScenario("rl").whenScenarioStateIs("second")
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "meta":{"regularMarketPrice":100.0,"chartPreviousClose":99.0,"currency":"USD"},
                        "indicators":{"quote":[{"close":[99.0,100.0]}]}
                    }],"error":null}}
                    """)));

        Quote q = fast.quote("AAPL");
        assertThat(q.price()).isEqualByComparingTo("100.0");
    }

    // --- ohlc() tests ---

    @Test
    void ohlcReturnsBarsOldestFirst() {
        // days=5 → range="5d"
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("range", equalTo("5d"))
                .withQueryParam("interval", equalTo("1d"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "timestamp":[1749600000,1749686400,1749772800],
                        "indicators":{"quote":[{
                            "open":[10.0,10.5,11.0],
                            "high":[11.0,11.0,11.5],
                            "low":[9.5,10.2,10.8],
                            "close":[10.5,10.8,11.2],
                            "volume":[1000,2000,3000]
                        }]}
                    }],"error":null}}
                    """)));

        var bars = provider.ohlc("AAPL", 5);

        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).date()).isBefore(bars.get(2).date());
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.5");
        assertThat(bars.get(2).close()).isEqualByComparingTo("11.2");
        assertThat(bars.get(2).high()).isEqualByComparingTo("11.5");
        assertThat(bars.get(0).volume()).isEqualTo(1000L);
    }

    @Test
    void ohlcSkipsNullCloseBars() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("range", equalTo("5d"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "timestamp":[1749600000,1749686400,1749772800],
                        "indicators":{"quote":[{
                            "open":[10.0,null,11.0],
                            "high":[11.0,null,11.5],
                            "low":[9.5,null,10.8],
                            "close":[10.5,null,11.2],
                            "volume":[1000,null,3000]
                        }]}
                    }],"error":null}}
                    """)));

        var bars = provider.ohlc("AAPL", 5);
        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.5");
        assertThat(bars.get(1).close()).isEqualByComparingTo("11.2");
    }

    @Test
    void ohlcEmptyResultThrowsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/ZZZZ"))
                .willReturn(okJson("""
                    {"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}
                    """)));

        assertThatThrownBy(() -> provider.ohlc("ZZZZ", 5))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test
    void ohlc5xxThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("range", equalTo("5d"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> provider.ohlc("AAPL", 5))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test
    void ohlcDayRangeMapping() {
        // Verify range mapping: days=30 → "1mo"
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("range", equalTo("1mo"))
                .withQueryParam("interval", equalTo("1d"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "timestamp":[1749600000],
                        "indicators":{"quote":[{"open":[10.0],"high":[11.0],"low":[9.5],"close":[10.5],"volume":[1000]}]}
                    }],"error":null}}
                    """)));

        var bars = provider.ohlc("AAPL", 30);
        assertThat(bars).hasSize(1);
    }

    @Test
    void ohlc429RetriesOnceAndSucceeds() {
        var fast = new YahooMarketDataProvider(wm.baseUrl(), "TestAgent/1.0", 0L);

        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .inScenario("rl").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("second"));
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .inScenario("rl").whenScenarioStateIs("second")
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "timestamp":[1717200000],
                        "indicators":{"quote":[{"open":[1.0],"high":[2.0],"low":[0.5],"close":[1.5],"volume":[100]}]}
                    }],"error":null}}
                    """)));

        var bars = fast.ohlc("AAPL", 5);
        assertThat(bars).hasSize(1);
    }

    @Test
    void nameIsYahoo() {
        assertThat(provider.name()).isEqualTo("yahoo");
    }

    @Test
    void ohlcEmptyTimestampsThrowsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(okJson("""
                    {"chart":{"result":[{"timestamp":[],
                      "indicators":{"quote":[{"open":[],"high":[],"low":[],"close":[],"volume":[]}]}}],"error":null}}
                    """)));
        assertThatThrownBy(() -> provider.ohlc("AAPL", 5))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    // --- H2: null O/H/L (with non-null close) must drop the bar, not fabricate ZERO ---

    @Test
    void ohlcSkipsBarWithNullLowButNonNullClose() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("range", equalTo("5d"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "timestamp":[1749600000,1749686400,1749772800],
                        "indicators":{"quote":[{
                            "open":[10.0,10.5,11.0],
                            "high":[11.0,11.0,11.5],
                            "low":[9.5,null,10.8],
                            "close":[10.5,10.8,11.2],
                            "volume":[1000,2000,3000]
                        }]}
                    }],"error":null}}
                    """)));

        var bars = provider.ohlc("AAPL", 5);

        assertThat(bars).hasSize(2);
        assertThat(bars).noneMatch(b -> b.low().compareTo(java.math.BigDecimal.ZERO) == 0);
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.5");
        assertThat(bars.get(1).close()).isEqualByComparingTo("11.2");
    }

    // --- M-D2: zero/missing price must throw NOT_FOUND, not cache a zero quote ---

    @Test
    void quoteZeroPriceWithNoFallbackCloseThrowsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "meta":{
                            "regularMarketPrice":0,
                            "chartPreviousClose":0,
                            "currency":"USD"
                        },
                        "indicators":{"quote":[{"close":[null]}]}
                    }],"error":null}}
                    """)));

        assertThatThrownBy(() -> provider.quote("AAPL"))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    // --- M-D4: GBp (London pence) normalizes to GBP / 100 ---

    @Test
    void quoteGbpNormalizesToGbpDividedBy100() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/VOD.L"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "meta":{
                            "regularMarketPrice":12345,
                            "chartPreviousClose":12000,
                            "currency":"GBp"
                        },
                        "indicators":{"quote":[{"close":[12000,12345]}]}
                    }],"error":null}}
                    """)));

        Quote q = provider.quote("VOD.L");

        assertThat(q.currency()).isEqualTo("GBP");
        assertThat(q.price()).isEqualByComparingTo("123.45");
    }

    // --- M-D3: bar timestamps map to LocalDate in the exchange's local timezone ---

    @Test
    void ohlcUsesExchangeTimezoneForBarDate() {
        // 1749772800 = 2025-06-13T00:00:00Z. In Australia/Sydney (UTC+10 in June, AEST) that's
        // already 2025-06-13T10:00 local — same calendar date here, so pick a timestamp that
        // actually straddles midnight UTC to prove the zone is applied: 1749772800 - 3600*2
        // = 1749765600 = 2025-06-12T22:00:00Z -> 2025-06-13T08:00 local in Sydney.
        long ts = 1749765600L;
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/BHP.AX"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                        "meta":{"exchangeTimezoneName":"Australia/Sydney","currency":"AUD"},
                        "timestamp":[%d],
                        "indicators":{"quote":[{
                            "open":[10.0],"high":[11.0],"low":[9.5],"close":[10.5],"volume":[1000]
                        }]}
                    }],"error":null}}
                    """.formatted(ts))));

        var bars = provider.ohlc("BHP.AX", 5);

        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).date()).isEqualTo(java.time.LocalDate.of(2025, 6, 13));
    }

    // --- M-D5: mapDaysToRange must cover the requested days window and trim to it ---

    @Test
    void ohlcCoversAndTrimsRequestedDaysWindow() {
        // days=260 must map to a range >= "2y" so the fixture (300 daily bars) actually
        // has enough data, and the result must be trimmed to exactly 260 bars.
        long baseTs = 1600000000L;
        StringBuilder ts = new StringBuilder();
        StringBuilder open = new StringBuilder();
        StringBuilder high = new StringBuilder();
        StringBuilder low = new StringBuilder();
        StringBuilder close = new StringBuilder();
        StringBuilder vol = new StringBuilder();
        int n = 300;
        for (int i = 0; i < n; i++) {
            if (i > 0) { ts.append(','); open.append(','); high.append(','); low.append(','); close.append(','); vol.append(','); }
            ts.append(baseTs + i * 86400L);
            open.append("10.0"); high.append("11.0"); low.append("9.0"); close.append("10.5"); vol.append("100");
        }
        String body = """
                {"chart":{"result":[{
                    "timestamp":[%s],
                    "indicators":{"quote":[{"open":[%s],"high":[%s],"low":[%s],"close":[%s],"volume":[%s]}]}
                }],"error":null}}
                """.formatted(ts, open, high, low, close, vol);

        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("range", equalTo("2y"))
                .willReturn(okJson(body)));

        var bars = provider.ohlc("AAPL", 260);

        assertThat(bars).hasSize(260);
    }

    @Test
    void mapDaysToRangeCoversDays260With2y() {
        assertThat(YahooMarketDataProvider.mapDaysToRange(260)).isEqualTo("2y");
    }

    @Test
    void mapDaysToRangeCoversMaxClampWith10y() {
        assertThat(YahooMarketDataProvider.mapDaysToRange(1825)).isEqualTo("10y");
    }

    // --- Lows: 404 must throw NOT_FOUND, not UNAVAILABLE ---

    @Test
    void quote404ThrowsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/ZZZZ"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> provider.quote("ZZZZ"))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test
    void ohlc404ThrowsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/ZZZZ"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> provider.ohlc("ZZZZ", 5))
                .isInstanceOfSatisfying(MarketDataException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }
}
