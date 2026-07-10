package de.visterion.agora.trading.saxo;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class SaxoInstrumentResolverTest {

    static WireMockServer wm;
    final AtomicLong now = new AtomicLong(0);

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private SaxoInstrumentResolver resolver(String exchangeId) {
        return new SaxoInstrumentResolver(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                () -> "Bearer t", exchangeId, 86_400_000L, now::get);
    }

    @Test
    void resolvesUniqueSymbol() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson("""
            {"Data":[{"Identifier":211,"AssetType":"Stock","Symbol":"AAPL:xnas","ExchangeId":"NASDAQ"}]}
            """)));
        var r = resolver(null).resolve("AAPL");
        assertThat(r.uic()).isEqualTo(211);
        assertThat(r.assetType()).isEqualTo("Stock");
        wm.verify(getRequestedFor(urlPathEqualTo("/ref/v1/instruments"))
                .withQueryParam("Keywords", equalTo("AAPL"))
                .withQueryParam("AssetTypes", equalTo("Stock"))
                .withHeader("Authorization", equalTo("Bearer t")));
    }

    @Test
    void keywordNoiseIsFilteredByExactBaseSymbolMatch() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson("""
            {"Data":[{"Identifier":211,"AssetType":"Stock","Symbol":"AAPL:xnas"},
                     {"Identifier":999,"AssetType":"Stock","Symbol":"AAPL34:bvmf"}]}
            """)));
        assertThat(resolver(null).resolve("AAPL").uic()).isEqualTo(211);
    }

    @Test
    void ambiguousAfterFilterThrows() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson("""
            {"Data":[{"Identifier":211,"AssetType":"Stock","Symbol":"AAPL:xnas"},
                     {"Identifier":212,"AssetType":"Stock","Symbol":"AAPL:xetr"}]}
            """)));
        assertThatThrownBy(() -> resolver(null).resolve("AAPL"))
                .isInstanceOf(SaxoInstrumentResolver.SymbolResolutionException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void unknownSymbolThrows() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .willReturn(okJson("{\"Data\":[]}")));
        assertThatThrownBy(() -> resolver(null).resolve("NOPE"))
                .isInstanceOf(SaxoInstrumentResolver.SymbolResolutionException.class)
                .hasMessageContaining("unknown symbol");
    }

    @Test
    void exchangeIdIsPassedWhenConfigured() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson("""
            {"Data":[{"Identifier":211,"AssetType":"Stock","Symbol":"AAPL:xnas"}]}
            """)));
        resolver("NASDAQ").resolve("AAPL");
        wm.verify(getRequestedFor(urlPathEqualTo("/ref/v1/instruments"))
                .withQueryParam("ExchangeId", equalTo("NASDAQ")));
    }

    @Test
    void negativeLookupIsCachedBriefly() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .willReturn(okJson("{\"Data\":[]}")));
        var r = resolver(null);

        assertThatThrownBy(() -> r.resolve("NOPE"))
                .isInstanceOf(SaxoInstrumentResolver.SymbolResolutionException.class);
        assertThatThrownBy(() -> r.resolve("NOPE"))
                .isInstanceOf(SaxoInstrumentResolver.SymbolResolutionException.class);

        wm.verify(1, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    @Test
    void negativeLookupExpiresAfterTtl() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .willReturn(okJson("{\"Data\":[]}")));
        var r = resolver(null);

        assertThatThrownBy(() -> r.resolve("NOPE")).isInstanceOf(SaxoInstrumentResolver.SymbolResolutionException.class);
        now.addAndGet(61_000L);   // past the 60s negative-cache TTL
        assertThatThrownBy(() -> r.resolve("NOPE")).isInstanceOf(SaxoInstrumentResolver.SymbolResolutionException.class);

        wm.verify(2, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    @Test
    void rateLimitedLookupThrowsNotReadyNotUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> resolver(null).resolve("AAPL"))
                .isInstanceOfSatisfying(de.visterion.agora.trading.BrokerException.class,
                        e -> assertThat(e.kind()).isEqualTo(de.visterion.agora.trading.BrokerException.Kind.NOT_READY));
    }

    @Test
    void secondResolveIsCached() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson("""
            {"Data":[{"Identifier":211,"AssetType":"Stock","Symbol":"AAPL:xnas"}]}
            """)));
        var r = resolver(null);
        r.resolve("AAPL");
        r.resolve("AAPL");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }
}
