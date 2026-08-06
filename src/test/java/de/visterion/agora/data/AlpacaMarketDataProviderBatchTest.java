package de.visterion.agora.data;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.fetch.alpaca.AlpacaDataClient;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

/**
 * Multi-symbol daily bars. Fixtures are hand-written and synthetic (SYNA/SYNB, invented prices) —
 * never a captured live response.
 */
class AlpacaMarketDataProviderBatchTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private AlpacaMarketDataProvider provider(boolean configured) {
        return new AlpacaMarketDataProvider(
                new AlpacaDataClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), configured));
    }

    private AlpacaMarketDataProvider provider(int batchSymbols, int batchMaxPages) {
        return new AlpacaMarketDataProvider(
                new AlpacaDataClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), true),
                batchSymbols, batchMaxPages);
    }

    /**
     * The wire contract measured on 2026-08-06: {@code limit} is a global bar budget across all
     * symbols, so a response can carry only part of the universe plus a {@code next_page_token}.
     * Without the page-follow loop this test fails — page 2's bars never arrive.
     */
    @Test void twoPageBatchMergesBothPagesPerSymbolOldestFirst() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/bars"))
                .withQueryParam("page_token", absent())
                .willReturn(okJson("""
                    {"bars":{
                       "SYNA":[{"t":"2025-01-02T05:00:00Z","o":10.0,"h":11.0,"l":9.5,"c":10.5,"v":1000},
                               {"t":"2025-01-03T05:00:00Z","o":10.5,"h":11.5,"l":10.2,"c":11.0,"v":1100}],
                       "SYNB":[{"t":"2025-01-02T05:00:00Z","o":20.0,"h":21.0,"l":19.5,"c":20.5,"v":2000}]
                     },"next_page_token":"SYNTHETIC-PAGE-2"}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/bars"))
                .withQueryParam("page_token", equalTo("SYNTHETIC-PAGE-2"))
                .willReturn(okJson("""
                    {"bars":{
                       "SYNA":[{"t":"2025-01-06T05:00:00Z","o":11.0,"h":12.0,"l":10.9,"c":11.8,"v":1200}],
                       "SYNB":[{"t":"2025-01-03T05:00:00Z","o":20.5,"h":21.5,"l":20.2,"c":21.0,"v":2100},
                               {"t":"2025-01-06T05:00:00Z","o":21.0,"h":22.0,"l":20.9,"c":21.8,"v":2200}]
                     },"next_page_token":null}
                    """)));

        Map<String, List<OhlcBar>> out = provider(true).ohlcBatch(List.of("SYNA", "SYNB"), 30);

        assertThat(out).containsOnlyKeys("SYNA", "SYNB");
        assertThat(out.get("SYNA")).extracting(b -> b.date().toString())
                .containsExactly("2025-01-02", "2025-01-03", "2025-01-06");
        assertThat(out.get("SYNB")).extracting(b -> b.date().toString())
                .containsExactly("2025-01-02", "2025-01-03", "2025-01-06");
        assertThat(out.get("SYNA").getLast().close()).isEqualByComparingTo("11.8");
        assertThat(out.get("SYNB").getLast().volume()).isEqualTo(2200L);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/v2/stocks/bars")));
    }

    @Test void symbolMissingFromTheResponseIsAbsentNotAnError() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/bars"))
                .willReturn(okJson("""
                    {"bars":{
                       "SYNA":[{"t":"2025-01-02T05:00:00Z","o":10.0,"h":11.0,"l":9.5,"c":10.5,"v":1000}]
                     },"next_page_token":null}
                    """)));

        Map<String, List<OhlcBar>> out =
                provider(true).ohlcBatch(List.of("SYNA", "SYNNOTREAL"), 30);

        assertThat(out).containsOnlyKeys("SYNA");
        assertThat(out).doesNotContainKey("SYNNOTREAL");
    }

    /** Both paths must produce the identical series for the same fixture data and days — if they
     *  could differ, a 52-week low would depend on which path fetched it. */
    @Test void batchSeriesEqualsSingleSymbolSeries() {
        String bars = """
            [{"t":"2025-01-02T05:00:00Z","o":10.0,"h":11.0,"l":9.5,"c":10.5,"v":1000},
             {"t":"2025-01-03T05:00:00Z","o":10.5,"h":11.5,"l":10.2,"c":11.0,"v":1100},
             {"t":"2025-01-06T05:00:00Z","o":11.0,"h":12.0,"l":10.9,"c":11.8,"v":1200}]
            """;
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/SYNA/bars"))
                .willReturn(okJson("{\"bars\":" + bars + ",\"symbol\":\"SYNA\",\"next_page_token\":null}")));
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/bars"))
                .willReturn(okJson("{\"bars\":{\"SYNA\":" + bars + "},\"next_page_token\":null}")));

        AlpacaMarketDataProvider p = provider(true);
        // days=2 < 3 bars, so both paths must apply the same trim-to-last-2.
        List<OhlcBar> single = p.ohlc("SYNA", 2);
        List<OhlcBar> batch = p.ohlcBatch(List.of("SYNA"), 2).get("SYNA");

        assertThat(batch).isEqualTo(single);
        assertThat(batch).hasSize(2);
        assertThat(batch.getFirst().date().toString()).isEqualTo("2025-01-03");
    }

    @Test void httpFailureOnPageTwoKeepsPageOneBars() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/bars"))
                .withQueryParam("page_token", absent())
                .willReturn(okJson("""
                    {"bars":{
                       "SYNA":[{"t":"2025-01-02T05:00:00Z","o":10.0,"h":11.0,"l":9.5,"c":10.5,"v":1000}]
                     },"next_page_token":"SYNTHETIC-PAGE-2"}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/bars"))
                .withQueryParam("page_token", equalTo("SYNTHETIC-PAGE-2"))
                .willReturn(aResponse().withStatus(429)));

        Map<String, List<OhlcBar>> out = provider(true).ohlcBatch(List.of("SYNA", "SYNB"), 30);

        assertThat(out).containsOnlyKeys("SYNA");
        assertThat(out.get("SYNA")).hasSize(1);
        assertThat(out.get("SYNA").getFirst().close()).isEqualByComparingTo("10.5");
    }

    @Test void symbolsAreSplitIntoChunksOfTheConfiguredSize() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/bars"))
                .willReturn(okJson("""
                    {"bars":{
                       "SYNA":[{"t":"2025-01-02T05:00:00Z","o":10.0,"h":11.0,"l":9.5,"c":10.5,"v":1000}]
                     },"next_page_token":null}
                    """)));

        provider(2, 50).ohlcBatch(List.of("SYNA", "SYNB", "SYNC"), 30);

        wm.verify(2, getRequestedFor(urlPathEqualTo("/v2/stocks/bars")));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/v2/stocks/bars"))
                .withQueryParam("symbols", equalTo("SYNA,SYNB")));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/v2/stocks/bars"))
                .withQueryParam("symbols", equalTo("SYNC")));
    }

    @Test void pageCapStopsAnEndlessTokenChain() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/bars"))
                .willReturn(okJson("""
                    {"bars":{
                       "SYNA":[{"t":"2025-01-02T05:00:00Z","o":10.0,"h":11.0,"l":9.5,"c":10.5,"v":1000}]
                     },"next_page_token":"ALWAYS-ANOTHER-PAGE"}
                    """)));

        Map<String, List<OhlcBar>> out = provider(100, 3).ohlcBatch(List.of("SYNA"), 30);

        wm.verify(3, getRequestedFor(urlPathEqualTo("/v2/stocks/bars")));
        assertThat(out.get("SYNA")).hasSize(3);
    }

    @Test void batchRequestsSplitAdjustedIexDailyBars() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/bars"))
                .willReturn(okJson("{\"bars\":{},\"next_page_token\":null}")));

        provider(true).ohlcBatch(List.of("SYNA"), 30);

        wm.verify(getRequestedFor(urlPathEqualTo("/v2/stocks/bars"))
                .withQueryParam("timeframe", equalTo("1Day"))
                .withQueryParam("feed", equalTo("iex"))
                .withQueryParam("adjustment", equalTo("split"))
                .withQueryParam("limit", equalTo("10000")));
    }

    @Test void blankKeyOhlcBatchThrowsUnavailable() {
        assertThatThrownBy(() -> provider(false).ohlcBatch(List.of("SYNA"), 30))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void emptySymbolListMakesNoCall() {
        assertThat(provider(true).ohlcBatch(List.of(), 30)).isEmpty();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/v2/stocks/bars")));
    }
}
