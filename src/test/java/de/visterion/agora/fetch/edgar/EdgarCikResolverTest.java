package de.visterion.agora.fetch.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class EdgarCikResolverTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private EdgarCikResolver resolver() {
        return new EdgarCikResolver(RestClient.builder().baseUrl(wm.baseUrl()).build());
    }

    private EdgarCikResolver resolver(AtomicLong clock) {
        return new EdgarCikResolver(RestClient.builder().baseUrl(wm.baseUrl()).build(), clock::get);
    }

    @Test void resolvesAndZeroPads() {
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .willReturn(okJson("""
                    {"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc"},
                     "1":{"cik_str":789019,"ticker":"MSFT","title":"Microsoft"}}
                    """)));
        assertThat(resolver().cik("aapl")).contains("0000320193");
    }

    @Test void unknownTickerEmpty() {
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .willReturn(okJson("{\"0\":{\"cik_str\":320193,\"ticker\":\"AAPL\"}}")));
        assertThat(resolver().cik("ZZZZ")).isEmpty();
    }

    @Test void blankTickerEmpty() {
        assertThat(resolver().cik("  ")).isEmpty();
    }

    @Test void emptyBodyDoesNotPoisonCache() {
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .inScenario("empty-then-valid").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{}"))
                .willSetStateTo("second"));
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .inScenario("empty-then-valid").whenScenarioStateIs("second")
                .willReturn(okJson("""
                    {"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc"}}
                    """)));

        EdgarCikResolver resolver = resolver();
        // First call: empty body → empty, but must NOT poison the cache.
        assertThat(resolver.cik("AAPL")).isEmpty();
        // Second call: valid body is fetched again (cache was not poisoned) → resolves.
        assertThat(resolver.cik("AAPL")).contains("0000320193");
    }

    // M-F6: share-class separator normalization ('.' <-> '-').
    @Test void resolvesShareClassSeparatorVariant() {
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .willReturn(okJson("""
                    {"0":{"cik_str":1067983,"ticker":"BRK-B","title":"Berkshire Hathaway"}}
                    """)));
        assertThat(resolver().cik("BRK.B")).contains("0001067983");
    }

    // M-F6: duplicate tickers in the SEC file keep the first (larger-cap) occurrence.
    @Test void duplicateTickerKeepsFirstOccurrence() {
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .willReturn(okJson("""
                    {"0":{"cik_str":111111,"ticker":"DUP","title":"Bigger Co"},
                     "1":{"cik_str":222222,"ticker":"DUP","title":"Smaller Co"}}
                    """)));
        assertThat(resolver().cik("DUP")).contains("0000111111");
    }

    // M-F7: 24h TTL — cache expiry triggers a re-fetch that picks up new data.
    @Test void expiredCacheTriggersRefetch() {
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .inScenario("ttl").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("""
                    {"0":{"cik_str":320193,"ticker":"AAPL"}}
                    """))
                .willSetStateTo("refreshed"));
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .inScenario("ttl").whenScenarioStateIs("refreshed")
                .willReturn(okJson("""
                    {"0":{"cik_str":999999,"ticker":"AAPL"}}
                    """)));

        AtomicLong clock = new AtomicLong(0);
        EdgarCikResolver resolver = resolver(clock);

        assertThat(resolver.cik("AAPL")).contains("0000320193");
        clock.addAndGet(Duration.ofHours(24).toMillis() + 1);
        assertThat(resolver.cik("AAPL")).contains("0000999999");
    }

    // M-F7/M-F8: an expired cache whose refresh fails keeps serving the stale map.
    @Test void failedRefreshServesStaleData() {
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .inScenario("stale").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("""
                    {"0":{"cik_str":320193,"ticker":"AAPL"}}
                    """))
                .willSetStateTo("down"));
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .inScenario("stale").whenScenarioStateIs("down")
                .willReturn(aResponse().withStatus(500)));

        AtomicLong clock = new AtomicLong(0);
        EdgarCikResolver resolver = resolver(clock);

        assertThat(resolver.cik("AAPL")).contains("0000320193");
        clock.addAndGet(Duration.ofHours(24).toMillis() + 1);
        assertThat(resolver.cik("AAPL")).contains("0000320193");
    }

    // M-F8: the HTTP fetch must not run inside the lock — two concurrent cold-start
    // callers must not serialize behind one another's slow fetch.
    @Test void doesNotSerializeConcurrentColdFetchesUnderLock() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .willReturn(okJson("""
                    {"0":{"cik_str":320193,"ticker":"AAPL"}}
                    """).withFixedDelay(1200)));

        EdgarCikResolver resolver = resolver();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long start = System.nanoTime();
            List<Future<Optional<String>>> futures = pool.invokeAll(List.of(
                    () -> resolver.cik("AAPL"),
                    () -> resolver.cik("AAPL")
            ));
            for (Future<Optional<String>> f : futures) {
                assertThat(f.get(3, TimeUnit.SECONDS)).contains("0000320193");
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // If the lock were held across the HTTP call, two sequential 1200ms fetches
            // would serialize to ~2400ms+. Not holding it keeps both roughly parallel.
            assertThat(elapsedMs).isLessThan(2200);
        } finally {
            pool.shutdownNow();
        }
    }
}
