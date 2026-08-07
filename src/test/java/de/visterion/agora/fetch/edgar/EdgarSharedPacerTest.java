package de.visterion.agora.fetch.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

/**
 * SEC's ceiling is per USER, not per class: "Current max request rate: 10 requests/second"
 * (Accessing EDGAR Data) and "no more than 10 requests per second, regardless of the number of
 * machines used to submit requests" (Internet Security Policy, both re-read 2026-08-07). So the
 * spacing budget has to be shared by every EDGAR client in the process, not owned by one of them.
 *
 * <p>Before this fix the pacer was one field per {@link EdgarSearchService} instance and the first
 * test below failed with {@code Expecting actual: [] to contain exactly: [110L]} — the second
 * client issued its request with no wait at all, because it had no budget to draw on.
 *
 * <p>The clock is frozen at 0 in every test here, so the remainder of the window is always the
 * WHOLE window and a recorded sleep of {@link EdgarRequestPacer#MIN_SPACING_MS} is exactly the
 * assertion "this request waited out the budget the previous client opened".
 */
class EdgarSharedPacerTest {

    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private static final LongSupplier FROZEN = () -> 0L;

    private static RestClient wmClient() {
        return de.visterion.agora.data.DataHttp.clientBuilder(15_000).baseUrl(wm.baseUrl()).build();
    }

    private final List<Long> sleeps = new ArrayList<>();
    private final EdgarSearchService.Sleeper recorder = sleeps::add;
    private final EdgarRequestPacer shared =
            new EdgarRequestPacer(EdgarRequestPacer.MIN_SPACING_MS, FROZEN, sleeps::add);

    private EdgarSearchService searchService() {
        return new EdgarSearchService(wmClient(), wmClient(), wm.baseUrl(), 3600L, FROZEN,
                recorder, 1024L, cik -> List.of(),
                EdgarSearchService.DEFAULT_MAX_CONCURRENT_FILING_FETCHES, 1_000L, shared);
    }

    private static void stubEftsEmpty() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("{\"hits\":{\"hits\":[]}}")));
    }

    // Synthetic, hand-written: a filer CIK of 42 exists nowhere in EDGAR.
    private static void stubSubmissions() {
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000000042.json"))
                .willReturn(okJson("""
                    {"filings":{"recent":{
                      "accessionNumber":["0000000042-25-000001"],
                      "form":["8-K"],
                      "filingDate":["2025-05-02"],
                      "reportDate":["2025-05-01"],
                      "primaryDocument":["synthetic-8k.htm"]
                    }}}
                    """)));
    }

    @Test void aRequestFromOneEdgarClientWaitsOutTheWindowOpenedByAnother() {
        stubEftsEmpty();
        stubSubmissions();
        EdgarSearchService search = searchService();
        EdgarService filings = new EdgarService(
                wmClient(), new EdgarCikResolver(wmClient()), 3600L, FROZEN, shared);

        // A: opens the window (first request ever on this budget — waits nothing).
        search.search(List.of("4"), null, LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 5);
        // B: a DIFFERENT EDGAR client, on data.sec.gov rather than efts.sec.gov. Same user, so
        // the same budget: it must wait out the remainder of A's window.
        filings.filings(null, "42", null, null, null, 5);

        assertThat(sleeps).containsExactly(EdgarRequestPacer.MIN_SPACING_MS);
    }

    // The ticker-file fetch is a www.sec.gov GET like any other; it used to run entirely outside
    // the budget, and it is issued exactly when EFTS hits are being parsed.
    @Test void theCikResolverTickerFileDrawsOnTheSameBudget() {
        stubEftsEmpty();
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .willReturn(okJson("{\"0\":{\"cik_str\":42,\"ticker\":\"ACME\"}}")));
        EdgarSearchService search = searchService();
        EdgarCikResolver resolver = new EdgarCikResolver(wmClient(), FROZEN, shared);

        search.search(List.of("4"), null, LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 5);
        assertThat(resolver.tickersForCik("42")).containsExactly("ACME");

        assertThat(sleeps).containsExactly(EdgarRequestPacer.MIN_SPACING_MS);
    }

    // get_filing_text's archive fetches were the largest unpaced path: a measured market-wide run
    // issued 1,095 archive GETs in ~150s, and they ran on an 8-permit semaphore with no throttle.
    @Test void filingTextFetchesDrawOnTheSameBudget() {
        stubEftsEmpty();
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/42/synthetic.htm"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html")
                        .withBody("<p>SUMMARY TERM SHEET</p><p>synthetic</p>")));
        EdgarSearchService search = searchService();

        search.search(List.of("4"), null, LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 5);
        search.filingText(wm.baseUrl() + "/Archives/edgar/data/42/synthetic.htm");

        assertThat(sleeps).containsExactly(EdgarRequestPacer.MIN_SPACING_MS);
    }

    /**
     * The 8-permit semaphore is a MEMORY bound and the pacer is a RATE bound; neither replaces the
     * other, so both stay. This pins the half that could plausibly have been dropped: with the
     * pacer installed on the same client, the concurrency bound still refuses the caller over the
     * bound instead of letting it buffer another multi-MB body.
     */
    @Test void theConcurrencyBoundStillRefusesOverTheBoundWithThePacerInstalled() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/42/slow.htm"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withFixedDelay(1500)
                        .withBody("<p>SUMMARY TERM SHEET</p><p>synthetic</p>")));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/42/second.htm"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withFixedDelay(1500)
                        .withBody("<p>SUMMARY TERM SHEET</p><p>synthetic</p>")));
        // One permit, 50ms queue wait — the bound made observable without 8 threads.
        var svc = new EdgarSearchService(wmClient(), wmClient(), wm.baseUrl(), 3600L, FROZEN,
                recorder, 1024L, cik -> List.of(), 1, 50L, shared);

        var started = new java.util.concurrent.CountDownLatch(1);
        var holder = new Thread(() -> {
            started.countDown();
            try { svc.filingText(wm.baseUrl() + "/Archives/edgar/data/42/slow.htm"); } catch (Exception ignored) { }
        });
        holder.start();
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);   // the holder is now inside the fetch, owning the only permit

        assertThatThrownBy(() -> svc.filingText(wm.baseUrl() + "/Archives/edgar/data/42/second.htm"))
                .isInstanceOf(de.visterion.agora.data.MarketDataException.class)
                .hasMessageStartingWith("filing_fetch_busy:");
        holder.join(10_000);
    }
}
