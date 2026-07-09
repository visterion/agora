package de.visterion.agora.data;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.fetch.alpaca.AlpacaDataClient;
import org.junit.jupiter.api.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class AlpacaMarketDataProviderTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private AlpacaMarketDataProvider provider(boolean configured) {
        return new AlpacaMarketDataProvider(
                new AlpacaDataClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), configured));
    }

    /** Alpaca data client with an explicit short read timeout, pointed at WireMock. */
    private AlpacaMarketDataProvider providerWithTimeout(long timeoutMs) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory();
        rf.setReadTimeout(Duration.ofMillis(timeoutMs));
        RestClient http = RestClient.builder().requestFactory(rf).baseUrl(wm.baseUrl()).build();
        return new AlpacaMarketDataProvider(new AlpacaDataClient(http, true));
    }

    @Test void nameIsAlpaca() { assertThat(provider(true).name()).isEqualTo("alpaca"); }

    @Test void quoteParsesPriceAndDayChangeFromSnapshot() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/AAPL/snapshot"))
                .withQueryParam("feed", equalTo("iex"))
                .willReturn(okJson("""
                    {
                      "latestTrade": {"t":"2025-01-03T20:00:00Z","p":190.5,"s":100,"x":"V"},
                      "latestQuote": {"t":"2025-01-03T20:00:00Z","ap":190.6,"bp":190.4},
                      "dailyBar": {"t":"2025-01-03T05:00:00Z","o":188.5,"h":191.0,"l":188.0,"c":190.2,"v":1000000},
                      "prevDailyBar": {"t":"2025-01-02T05:00:00Z","o":186.0,"h":188.5,"l":185.5,"c":188.1,"v":900000}
                    }
                    """)));
        Quote q = provider(true).quote("AAPL");
        assertThat(q.symbol()).isEqualTo("AAPL");
        assertThat(q.price()).isEqualByComparingTo("190.5");
        assertThat(q.currency()).isEqualTo("USD");
        // (190.5 - 188.1) / 188.1 * 100 = 1.2759...
        assertThat(q.dayChangePercent()).isEqualByComparingTo("1.2759");
    }

    @Test void quoteFallsBackToDailyBarCloseWhenNoTrade() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/AAPL/snapshot"))
                .willReturn(okJson("""
                    {
                      "dailyBar": {"t":"2025-01-03T05:00:00Z","o":188.5,"h":191.0,"l":188.0,"c":190.2,"v":1000000},
                      "prevDailyBar": {"t":"2025-01-02T05:00:00Z","c":188.1}
                    }
                    """)));
        Quote q = provider(true).quote("AAPL");
        assertThat(q.price()).isEqualByComparingTo("190.2");
    }

    @Test void quoteEmptySnapshotThrowsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/ZZZZ/snapshot"))
                .willReturn(okJson("{}")));
        assertThatThrownBy(() -> provider(true).quote("ZZZZ"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test void ohlcParsesBarsOldestFirst() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/AAPL/bars"))
                .withQueryParam("timeframe", equalTo("1Day"))
                .withQueryParam("feed", equalTo("iex"))
                .willReturn(okJson("""
                    {"bars":[
                      {"t":"2025-01-02T05:00:00Z","o":10.0,"h":11.0,"l":9.5,"c":10.5,"v":1000},
                      {"t":"2025-01-03T05:00:00Z","o":11.0,"h":11.5,"l":10.8,"c":11.2,"v":3000}
                    ],"symbol":"AAPL","next_page_token":null}
                    """)));
        List<OhlcBar> bars = provider(true).ohlc("AAPL", 30);
        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).date()).isBefore(bars.get(1).date());
        assertThat(bars.get(0).date().toString()).isEqualTo("2025-01-02");
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.5");
        assertThat(bars.get(1).close()).isEqualByComparingTo("11.2");
        assertThat(bars.get(1).volume()).isEqualTo(3000L);
    }

    @Test void ohlcUnknownSymbolThrowsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/SAP.DE/bars"))
                .willReturn(okJson("{\"bars\":null,\"next_page_token\":null,\"symbol\":\"SAP.DE\"}")));
        assertThatThrownBy(() -> provider(true).ohlc("SAP.DE", 5))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test void blankKeyQuoteThrowsUnavailable() {
        assertThatThrownBy(() -> provider(false).quote("AAPL"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void blankKeyOhlcThrowsUnavailable() {
        assertThatThrownBy(() -> provider(false).ohlc("AAPL", 30))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void non2xxQuoteThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/AAPL/snapshot"))
                .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> provider(true).quote("AAPL"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void non2xxOhlcThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/AAPL/bars"))
                .willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> provider(true).ohlc("AAPL", 30))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void slowResponseBeyondTimeoutThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/v2/stocks/AAPL/snapshot"))
                .willReturn(okJson("{\"latestTrade\":{\"p\":190.5}}").withFixedDelay(1500)));
        assertThatThrownBy(() -> providerWithTimeout(200).quote("AAPL"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }
}
