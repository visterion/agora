package de.visterion.agora.data;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.trading.saxo.SaxoDataAccess;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class SaxoDataSymbolResolverTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    static final String SAP_SEARCH = """
        {"Data":[
          {"AssetType":"Stock","CurrencyCode":"USD","Description":"SAP SE - ADR","ExchangeId":"NYSE","Identifier":6218,"Symbol":"SAP:xnys"},
          {"AssetType":"Stock","CurrencyCode":"EUR","Description":"SAP SE","ExchangeId":"FSE","Identifier":1126,"Symbol":"SAPG:xetr"},
          {"AssetType":"Stock","CurrencyCode":"ZAR","Description":"Sappi Ltd","ExchangeId":"JSE","Identifier":57535,"Symbol":"SAP:xjse"}
        ]}
        """;

    private final AtomicLong clock = new AtomicLong(0);

    private SaxoDataSymbolResolver resolver(boolean withBearer) {
        SaxoDataAccess access = new SaxoDataAccess(
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                () -> withBearer ? Optional.of("Bearer t") : Optional.empty());
        return new SaxoDataSymbolResolver(access, clock::get);
    }

    @Test void suffixDeQueriesFseAndPicksExchangeHit() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .withQueryParam("Keywords", equalTo("SAP"))
                .withQueryParam("AssetTypes", equalTo("Stock"))
                .withQueryParam("ExchangeId", equalTo("FSE"))
                .willReturn(okJson(SAP_SEARCH)));
        assertThat(resolver(true).resolve("SAP.DE")).isEqualTo(1126L);
    }

    @Test void noSuffixRequiresExactUsTickerMatch() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .withQueryParam("Keywords", equalTo("SAP"))
                .willReturn(okJson(SAP_SEARCH)));
        // "SAP" matches SAP:xnys (NYSE, exact ticker before ':')
        assertThat(resolver(true).resolve("SAP")).isEqualTo(6218L);
    }

    @Test void noSuffixWithoutUsMatchIsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments"))
                .willReturn(okJson("""
                    {"Data":[{"AssetType":"Stock","CurrencyCode":"EUR","ExchangeId":"FSE","Identifier":1126,"Symbol":"SAPG:xetr"}]}
                    """)));
        assertThatThrownBy(() -> resolver(true).resolve("SAPG"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test void unknownSuffixIsUnavailableWithoutHttpCall() {
        assertThatThrownBy(() -> resolver(true).resolve("AIR.PA"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
        wm.verify(0, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    @Test void noBearerIsUnavailable() {
        assertThatThrownBy(() -> resolver(false).resolve("SAP.DE"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void httpErrorIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(status(429)));
        assertThatThrownBy(() -> resolver(true).resolve("SAP.DE"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void emptySearchResultIsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson("{\"Data\":[]}")));
        assertThatThrownBy(() -> resolver(true).resolve("SAP.DE"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test void uicResolutionFailureIsNegativelyCachedForSixtySeconds() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson("{\"Data\":[]}")));
        SaxoDataSymbolResolver r = resolver(true);
        assertThatThrownBy(() -> r.resolve("SAP.DE")).isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> r.resolve("SAP.DE")).isInstanceOf(MarketDataException.class);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));   // second call served from negative cache
        clock.set(60_001L);   // past negative-cache TTL → re-lookup
        assertThatThrownBy(() -> r.resolve("SAP.DE")).isInstanceOf(MarketDataException.class);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    @Test void uicIsCachedFor24Hours() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson(SAP_SEARCH)));
        SaxoDataSymbolResolver r = resolver(true);
        assertThat(r.resolve("SAP.DE")).isEqualTo(1126L);
        assertThat(r.resolve("SAP.DE")).isEqualTo(1126L);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
        clock.set(24 * 3600 * 1000L + 1);   // past TTL → re-lookup
        assertThat(r.resolve("SAP.DE")).isEqualTo(1126L);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }
}
