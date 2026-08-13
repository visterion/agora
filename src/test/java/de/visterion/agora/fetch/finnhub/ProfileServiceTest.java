package de.visterion.agora.fetch.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileServiceTest {

    private WireMockServer wm;

    @BeforeEach void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterEach void stop() { wm.stop(); }

    private ProfileService service(String key) {
        return service(key, mock(YahooCompanyDataSource.class));
    }

    private ProfileService service(String key, YahooCompanyDataSource yahoo) {
        RestClient http = RestClient.builder().baseUrl(wm.baseUrl()).build();
        FinnhubClient client = new FinnhubClient(http, key);
        return new ProfileService(client, 120L, System::currentTimeMillis, 604800L, yahoo);
    }

    @Test void returnsRawProfileObject() {
        wm.stubFor(get(urlPathEqualTo("/stock/profile2"))
                .willReturn(okJson("{\"name\":\"Apple Inc\",\"finnhubIndustry\":\"Technology\",\"exchange\":\"NASDAQ\"}")));
        Profile p = service("k").profile("AAPL");
        assertThat(p.symbol()).isEqualTo("AAPL");
        assertThat(p.profile().path("finnhubIndustry").asString("")).isEqualTo("Technology");
    }

    @Test void blankKeyThrowsUnavailable() {
        assertThatThrownBy(() -> service("").profile("AAPL")).isInstanceOf(MarketDataException.class);
    }

    @Test void emptyBodyThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/stock/profile2")).willReturn(okJson("{}")));
        assertThatThrownBy(() -> service("k").profile("AAPL")).isInstanceOf(MarketDataException.class);
    }

    @Test void nonUsSymbolSkipsFinnhub() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        var profileNode = new tools.jackson.databind.ObjectMapper().createObjectNode();
        when(yahoo.profile("SAP.DE")).thenReturn(new Profile("SAP.DE", profileNode));

        Profile p = service("k", yahoo).profile("SAP.DE");

        assertThat(p).isNotNull();
        assertThat(p.symbol()).isEqualTo("SAP.DE");
        assertThat(p.profile()).isNotNull();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/stock/profile2")));
    }

    @Test void usSymbolStillCallsFinnhub() {
        wm.stubFor(get(urlPathEqualTo("/stock/profile2"))
                .willReturn(okJson("{\"name\":\"Apple Inc\",\"finnhubIndustry\":\"Technology\",\"exchange\":\"NASDAQ\"}")));
        Profile p = service("k").profile("AAPL");
        assertThat(p.symbol()).isEqualTo("AAPL");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/stock/profile2")));
    }

    @Test void nonUs_returnsYahooProfile() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        var profileNode = new tools.jackson.databind.ObjectMapper().createObjectNode().put("finnhubIndustry", "Technology");
        when(yahoo.profile("SAP.DE")).thenReturn(new Profile("SAP.DE", profileNode));

        Profile p = service("k", yahoo).profile("SAP.DE");

        assertThat(p.profile().path("finnhubIndustry").asString("")).isEqualTo("Technology");
        wm.verify(0, getRequestedFor(urlPathEqualTo("/stock/profile2")));
    }

    @Test void nonUs_yahooThrows_degradesToEmptyNonNull_notCached() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        when(yahoo.profile("SAP.DE"))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "yahoo down", null));
        ProfileService svc = service("k", yahoo);

        Profile p1 = svc.profile("SAP.DE");
        assertThat(p1).isNotNull();
        assertThat(p1.symbol()).isEqualTo("SAP.DE");
        assertThat(p1.profile()).isNotNull();
        assertThat(p1.profile().isEmpty()).isTrue();

        Profile p2 = svc.profile("SAP.DE");
        assertThat(p2.profile().isEmpty()).isTrue();

        verify(yahoo, times(2)).profile("SAP.DE");
    }

    @Test void nonUs_withoutFinnhubKey_usesYahoo() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        var profileNode = new tools.jackson.databind.ObjectMapper().createObjectNode().put("finnhubIndustry", "Technology");
        when(yahoo.profile("SAP.DE")).thenReturn(new Profile("SAP.DE", profileNode));

        Profile p = service("", yahoo).profile("SAP.DE");

        assertThat(p.profile().path("finnhubIndustry").asString("")).isEqualTo("Technology");
    }

    /**
     * Builds the ProfileService bean through its real {@code @Autowired} constructor (no
     * property overrides for the finnhub-profile TTL), so the finnhub cache gets whatever TTL
     * {@code application.yaml}/the {@code @Value} default actually resolves to today — the
     * exact wiring that is broken in production (bound to the 6h {@code fundamentals-seconds}
     * key, so Lazarus's nightly run always finds a cold cache). The cache's clock is then
     * swapped out (reflection) for a controllable one, keeping the production-resolved TTL, so
     * the test observes real wiring instead of a TTL chosen by the test itself.
     */
    private ProfileService productionWiredService(long[] clock) {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(FinnhubClient.class, () -> new FinnhubClient(
                        RestClient.builder().baseUrl(wm.baseUrl()).build(), "k"))
                .withBean(YahooCompanyDataSource.class, () -> mock(YahooCompanyDataSource.class))
                .withConfiguration(org.springframework.boot.context.annotation.UserConfigurations.of(ProfileService.class));

        ProfileService[] holder = new ProfileService[1];
        runner.run(ctx -> holder[0] = ctx.getBean(ProfileService.class));
        ProfileService svc = holder[0];

        Object finnhubCache = org.springframework.test.util.ReflectionTestUtils.getField(svc, "cache");
        long ttlMillis = (long) org.springframework.test.util.ReflectionTestUtils.getField(finnhubCache, "ttlMillis");
        long maxSize = (long) org.springframework.test.util.ReflectionTestUtils.getField(finnhubCache, "maxSize");
        var replacementCache = new de.visterion.agora.data.TtlCache<String, Profile>(ttlMillis, maxSize, () -> clock[0]);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "cache", replacementCache);
        return svc;
    }

    @Test void finnhubProfileSurvives7hJump() {
        wm.stubFor(get(urlPathEqualTo("/stock/profile2"))
                .willReturn(okJson("{\"name\":\"Synth Inc\",\"finnhubIndustry\":\"Technology\",\"exchange\":\"NASDAQ\"}")));
        long[] clock = {0L};
        ProfileService svc = productionWiredService(clock);

        svc.profile("SYNTH");
        clock[0] += 7L * 3600 * 1000; // +7h
        svc.profile("SYNTH");

        wm.verify(1, getRequestedFor(urlPathEqualTo("/stock/profile2")));
    }

    @Test void finnhubProfileRefetchesAfter8Days() {
        wm.stubFor(get(urlPathEqualTo("/stock/profile2"))
                .willReturn(okJson("{\"name\":\"Synth Inc\",\"finnhubIndustry\":\"Technology\",\"exchange\":\"NASDAQ\"}")));
        long[] clock = {0L};
        ProfileService svc = productionWiredService(clock);

        svc.profile("SYNTH");
        clock[0] += 8L * 24 * 3600 * 1000; // +8 days
        svc.profile("SYNTH");

        wm.verify(2, getRequestedFor(urlPathEqualTo("/stock/profile2")));
    }

    @Test void us_unchanged() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        wm.stubFor(get(urlPathEqualTo("/stock/profile2"))
                .willReturn(okJson("{\"name\":\"Apple Inc\",\"finnhubIndustry\":\"Technology\",\"exchange\":\"NASDAQ\"}")));

        Profile p = service("k", yahoo).profile("AAPL");

        assertThat(p.symbol()).isEqualTo("AAPL");
        verifyNoInteractions(yahoo);
    }
}
