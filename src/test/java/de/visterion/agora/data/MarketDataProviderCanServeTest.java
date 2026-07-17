package de.visterion.agora.data;

import de.visterion.agora.fetch.alpaca.AlpacaDataClient;
import de.visterion.agora.trading.saxo.SaxoDataAccess;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exchange-aware routing (canServe): US-only providers (Alpaca, TwelveData, Finnhub) must
 * decline non-US instruments so {@code MarketDataService.firstSuccess} skips a guaranteed 4xx
 * and reaches Saxo/Yahoo directly. Global providers (Saxo, Yahoo) keep the default (serve all).
 */
class MarketDataProviderCanServeTest {

    private static Instrument instrument(String display) {
        return Instrument.raw(display);
    }

    /** No exchange suffix, but an explicit {@code countryCode} (e.g. resolved via Saxo). */
    private static Instrument instrumentWithCountryCode(String display, String countryCode) {
        return new Instrument(display, display, null, null, null, null, null, countryCode, "Stock", false, 1.0);
    }

    private final AlpacaMarketDataProvider alpaca =
            new AlpacaMarketDataProvider(new AlpacaDataClient(RestClient.builder().baseUrl("http://unused").build(), false));
    private final TwelveDataMarketDataProvider twelveData =
            new TwelveDataMarketDataProvider("http://unused", "");
    private final FinnhubMarketDataProvider finnhub =
            new FinnhubMarketDataProvider("http://unused", "");
    private final SaxoMarketDataProvider saxo =
            new SaxoMarketDataProvider(Mockito.mock(SaxoDataAccess.class));
    private final YahooMarketDataProvider yahoo =
            new YahooMarketDataProvider("http://unused", "test-agent", 0L);

    @Test void usOnlyProvidersServeUsOnly() {
        for (MarketDataProvider p : List.of(alpaca, twelveData, finnhub)) {
            assertThat(p.canServe(instrument("AAPL"))).as(p.name() + " AAPL").isTrue();       // US
            assertThat(p.canServe(instrument("ALV.DE"))).as(p.name() + " ALV.DE").isFalse();  // Xetra
            assertThat(p.canServe(instrument("0700.HK"))).as(p.name() + " 0700.HK").isFalse();// HK
            assertThat(p.canServe(instrument("6758.T"))).as(p.name() + " 6758.T").isFalse();  // Tokyo
            assertThat(p.canServe(instrument("NOVO-B.CO"))).as(p.name() + " NOVO-B.CO").isFalse(); // Nordic
            assertThat(p.canServe(instrument("BYDDY"))).as(p.name() + " BYDDY").isTrue();     // US-format ADR — out of scope
        }
    }

    @Test void usOnlyProvidersRespectCountryCodeWithoutSuffix() {
        for (MarketDataProvider p : List.of(alpaca, twelveData, finnhub)) {
            assertThat(p.canServe(instrumentWithCountryCode("SOMESYM", "DE")))
                    .as(p.name() + " countryCode=DE, no suffix").isFalse();
            assertThat(p.canServe(instrumentWithCountryCode("SOMESYM", "US")))
                    .as(p.name() + " countryCode=US, no suffix").isTrue();
        }
    }

    @Test void globalProvidersServeAll() {
        for (MarketDataProvider p : List.of(saxo, yahoo)) {
            assertThat(p.canServe(instrument("ALV.DE"))).as(p.name() + " ALV.DE").isTrue();
            assertThat(p.canServe(instrument("AAPL"))).as(p.name() + " AAPL").isTrue();
        }
    }
}
