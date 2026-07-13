package de.visterion.agora.data;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.trading.saxo.SaxoDataAccess;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class InstrumentResolverTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }
    private final AtomicLong clock = new AtomicLong(0);

    static final String SAP_SEARCH = """
        {"Data":[
          {"AssetType":"Stock","CurrencyCode":"USD","ExchangeId":"NYSE","Identifier":6218,"Symbol":"SAP:xnys"},
          {"AssetType":"Stock","CurrencyCode":"EUR","ExchangeId":"FSE","Identifier":1126,"Symbol":"SAPG:xetr"}
        ]}""";

    private SaxoInstrumentResolver resolver(boolean withBearer) {
        SaxoDataAccess access = new SaxoDataAccess(
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                () -> withBearer ? Optional.of("Bearer t") : Optional.empty());
        return new SaxoInstrumentResolver(access, clock::get);
    }

    @Test void bareUsTickerReturnsRawWithoutHttp() {
        Instrument i = resolver(true).resolve("AAPL");
        assertThat(i.resolved()).isFalse();
        assertThat(i.displaySymbol()).isEqualTo("AAPL");
        wm.verify(0, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    @Test void dottedUnmappedReturnsRawWithoutHttp() {
        assertThat(resolver(true).resolve("XYZ.OL").resolved()).isFalse();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    @Test void noBearerReturnsRawWithoutHttp() {
        assertThat(resolver(false).resolve("SAP.DE").resolved()).isFalse();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    @Test void blankOrNullReturnsRaw() {
        assertThat(resolver(true).resolve("   ").resolved()).isFalse();
        assertThat(resolver(true).resolve(null).resolved()).isFalse();
    }

    @Test void suffixResolvesToUicAndCurrency() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .withQueryParam("ExchangeId", equalTo("FSE")).willReturn(okJson(SAP_SEARCH)));
        Instrument i = resolver(true).resolve("SAP.DE");
        assertThat(i.resolved()).isTrue();
        assertThat(i.uic()).isEqualTo(1126L);
        assertThat(i.currencyCode()).isEqualTo("EUR");
        assertThat(i.displaySymbol()).isEqualTo("SAP.DE");   // raw, not SAPG
    }

    @Test void emptySearchReturnsRawAndIsNegativelyCached() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson("{\"Data\":[]}")));
        SaxoInstrumentResolver r = resolver(true);
        assertThat(r.resolve("SAP.DE").resolved()).isFalse();
        assertThat(r.resolve("SAP.DE").resolved()).isFalse();
        wm.verify(1, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));   // 2nd from negative cache
        clock.set(60_001L);
        r.resolve("SAP.DE");
        wm.verify(2, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    @Test void httpErrorReturnsRaw() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(status(429)));
        assertThat(resolver(true).resolve("SAP.DE").resolved()).isFalse();
    }

    @Test void resolvedSuffixIsCachedFor24h() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson(SAP_SEARCH)));
        SaxoInstrumentResolver r = resolver(true);
        r.resolve("SAP.DE"); r.resolve("SAP.DE");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
        clock.set(24 * 3600 * 1000L + 1);
        r.resolve("SAP.DE");
        wm.verify(2, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    static final String ISIN_SEARCH = """
        {"Data":[
          {"AssetType":"Stock","CurrencyCode":"GBp","ExchangeId":"LSE_INTL","Identifier":9001,"Symbol":"SAP:xlon"},
          {"AssetType":"Stock","CurrencyCode":"EUR","ExchangeId":"FSE","Identifier":1126,"Symbol":"SAPG:xetr"}
        ]}""";

    @Test void isinPicksDomesticVenueAndEnrichesFromDetails() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .withQueryParam("Keywords", equalTo("DE0007164600")).willReturn(okJson(ISIN_SEARCH)));
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments/details/1126")).willReturn(okJson("""
            {"Uic":1126,"Isin":"DE0007164600","ExchangeId":"FSE","CurrencyCode":"EUR","CountryCode":"DE"}""")));
        Instrument i = resolver(true).resolve("DE0007164600");
        assertThat(i.uic()).isEqualTo(1126L);              // German ISIN → Xetra (EUR), not LSE (GBp)
        assertThat(i.isin()).isEqualTo("DE0007164600");
        assertThat(i.currencyCode()).isEqualTo("EUR");
        assertThat(i.displaySymbol()).isEqualTo("DE0007164600");
    }
}
