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
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments/details/1126/Stock")).willReturn(okJson("""
            {"Uic":1126,"ExchangeId":"FSE","CurrencyCode":"EUR","PriceToContractFactor":1.0}""")));
        Instrument i = resolver(true).resolve("SAP.DE");
        assertThat(i.resolved()).isTrue();
        assertThat(i.uic()).isEqualTo(1126L);
        assertThat(i.currencyCode()).isEqualTo("EUR");
        assertThat(i.displaySymbol()).isEqualTo("SAP.DE");   // raw, not SAPG
    }

    @Test void suffixEnrichesFactorAndSettlementCurrencyFromDetails() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .withQueryParam("ExchangeId", equalTo("FSE"))
                .willReturn(okJson("""
                  {"Data":[{"AssetType":"Stock","CurrencyCode":"EUR","ExchangeId":"FSE","Identifier":1126,"Symbol":"SAPG:xetr"}]}""")));
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments/details/1126/Stock")).willReturn(okJson("""
            {"Uic":1126,"ExchangeId":"FSE","CurrencyCode":"EUR","CountryCode":"DE","Mic":"XETR","PriceToContractFactor":1.0}""")));
        Instrument i = resolver(true).resolve("SAP.DE");
        assertThat(i.uic()).isEqualTo(1126L);
        assertThat(i.priceToContractFactor()).isEqualTo(1.0);
        assertThat(i.mic()).isEqualTo("XETR");
        assertThat(i.currencyCode()).isEqualTo("EUR");
        assertThat(i.displaySymbol()).isEqualTo("SAP.DE");
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
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments/details/1126/Stock")).willReturn(okJson("""
            {"Uic":1126,"ExchangeId":"FSE","CurrencyCode":"EUR","PriceToContractFactor":1.0}""")));
        SaxoInstrumentResolver r = resolver(true);
        assertThat(r.resolve("SAP.DE").resolved()).isTrue();
        assertThat(r.resolve("SAP.DE").resolved()).isTrue();
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
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments/details/1126/Stock")).willReturn(okJson("""
            {"Uic":1126,"Isin":"DE0007164600","ExchangeId":"FSE","CurrencyCode":"EUR","CountryCode":"DE"}""")));
        Instrument i = resolver(true).resolve("DE0007164600");
        assertThat(i.uic()).isEqualTo(1126L);              // German ISIN → Xetra (EUR), not LSE (GBp)
        assertThat(i.isin()).isEqualTo("DE0007164600");
        assertThat(i.currencyCode()).isEqualTo("EUR");
        assertThat(i.displaySymbol()).isEqualTo("DE0007164600");
    }

    @Test void isinEnrichesFactorFromDetails() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .withQueryParam("Keywords", equalTo("DE0007164600")).willReturn(okJson(ISIN_SEARCH)));
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments/details/1126/Stock")).willReturn(okJson("""
            {"Uic":1126,"Isin":"DE0007164600","ExchangeId":"FSE","CurrencyCode":"EUR","CountryCode":"DE","PriceToContractFactor":1.0}""")));
        Instrument i = resolver(true).resolve("DE0007164600");
        assertThat(i.uic()).isEqualTo(1126L);
        assertThat(i.priceToContractFactor()).isEqualTo(1.0);
    }

    @Test void transientDetailsFailureIsNotCached() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .withQueryParam("Keywords", equalTo("DE0007164600")).willReturn(okJson("""
            {"Data":[{"AssetType":"Stock","CurrencyCode":"EUR","ExchangeId":"FSE","Identifier":1126,"Symbol":"SAPG:xetr"}]}""")));

        // WireMock scenario: first TWO calls return 500 (within first resolve, then on second resolve),
        // then third call onwards returns 200. This ensures first resolution fails completely.
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments/details/1126/Stock"))
                .inScenario("detailsFailure")
                .whenScenarioStateIs("Started")
                .willReturn(status(500))
                .willSetStateTo("step1"));
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments/details/1126/Stock"))
                .inScenario("detailsFailure")
                .whenScenarioStateIs("step1")
                .willReturn(status(500))
                .willSetStateTo("recovered"));
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments/details/1126/Stock"))
                .inScenario("detailsFailure")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("""
            {"Uic":1126,"Isin":"DE0007164600","ExchangeId":"FSE","CurrencyCode":"EUR","CountryCode":"DE"}""")));

        SaxoInstrumentResolver r = resolver(true);

        // First call: details fails with 500 (twice within lookupIsin: once in loop, once in fallback),
        // so ISIN resolution fails and returns raw
        Instrument i1 = r.resolve("DE0007164600");
        assertThat(i1.resolved()).isFalse();
        assertThat(i1.displaySymbol()).isEqualTo("DE0007164600");

        // Advance clock past the negative cache TTL (60 seconds) to clear failure cache
        clock.set(61_000L);

        // Second call: details now succeeds, and ISIN resolves because the details failure was NOT cached
        // (only the resolution failure was cached in failureCache, which we just cleared)
        Instrument i2 = r.resolve("DE0007164600");
        assertThat(i2.resolved()).isTrue();
        assertThat(i2.uic()).isEqualTo(1126L);
        assertThat(i2.currencyCode()).isEqualTo("EUR");

        // Verify the details endpoint was called three times:
        // - 2 times in first resolve (both failed)
        // - 1 time in second resolve (succeeded)
        wm.verify(3, getRequestedFor(urlPathEqualTo("/ref/v1/instruments/details/1126/Stock")));
    }

    @Test void londonSuffixIsMappedAndResolvesViaLseSets() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .withQueryParam("ExchangeId", equalTo("LSE_SETS"))
                .willReturn(okJson("""
                  {"Data":[{"AssetType":"Stock","CurrencyCode":"GBP","ExchangeId":"LSE_SETS","Identifier":899,"Symbol":"VOD:xlon"}]}""")));
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments/details/899/Stock")).willReturn(okJson("""
            {"Uic":899,"ExchangeId":"LSE_SETS","CurrencyCode":"GBP","CountryCode":"GB","Mic":"XLON","PriceToContractFactor":0.01}""")));
        Instrument i = resolver(true).resolve("VOD.L");
        assertThat(i.uic()).isEqualTo(899L);
        assertThat(i.priceToContractFactor()).isEqualTo(0.01);
        assertThat(i.currencyCode()).isEqualTo("GBP");
    }
}
