package de.visterion.agora.fetch.earnings;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class EarningsServiceTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-07-27");
    private static final LocalDate FROM = TODAY.minusDays(30);
    private static final LocalDate TO = TODAY.plusDays(30);

    private final AtomicLong clock = new AtomicLong(0L);

    /** Counting fake so tests can assert a cached call issued no provider work. */
    private static final class Fake implements EarningsProvider {
        final String name;
        final EarningsCoverage coverage;
        final List<EarningsEvent> events;
        final boolean fails;
        final AtomicInteger calls = new AtomicInteger();

        Fake(String name, EarningsCoverage coverage, List<EarningsEvent> events, boolean fails) {
            this.name = name; this.coverage = coverage; this.events = events; this.fails = fails;
        }
        public String name() { return name; }
        public EarningsCoverage coverage() { return coverage; }
        public List<EarningsEvent> earnings(String s, LocalDate f, LocalDate t) {
            calls.incrementAndGet();
            if (fails) throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, name + " down", null);
            return events;
        }
    }

    private static Fake ok(String n, EarningsCoverage c, List<EarningsEvent> ev) {
        return new Fake(n, c, ev, false);
    }
    private static Fake dead(String n, EarningsCoverage c) {
        return new Fake(n, c, List.of(), true);
    }
    private static EarningsEvent ev(String sym, String date) {
        return new EarningsEvent(sym, LocalDate.parse(date), new BigDecimal("1.0"), null, null, null, null);
    }

    /** Like {@link Fake} but its failure can be toggled after construction, to test a provider
     *  that recovers between calls under the same cache key. */
    private static final class Toggle implements EarningsProvider {
        final String name;
        final EarningsCoverage coverage;
        final List<EarningsEvent> events;
        final AtomicBoolean failing;
        final AtomicInteger calls = new AtomicInteger();

        Toggle(String name, EarningsCoverage coverage, List<EarningsEvent> events, boolean failing) {
            this.name = name; this.coverage = coverage; this.events = events;
            this.failing = new AtomicBoolean(failing);
        }
        public String name() { return name; }
        public EarningsCoverage coverage() { return coverage; }
        public List<EarningsEvent> earnings(String s, LocalDate f, LocalDate t) {
            calls.incrementAndGet();
            if (failing.get()) throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, name + " down", null);
            return events;
        }
    }

    private EarningsService service(List<EarningsProvider> providers) {
        return new EarningsService(providers, 21600L, 600L, 3, 600_000L,
                7000L, clock::get, () -> TODAY);
    }

    // ---- P1 regression anchors -------------------------------------------------

    @Test void finnhubEmptyAndYahooDeadYieldsEmptyPartialCachedShort() {
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of());
        var yahoo = dead("yahoo", EarningsCoverage.FULL_WINDOW);
        var svc = service(List.of(finnhub, yahoo));

        var r = svc.earnings("ZZTOP", FROM, TO);

        assertThat(r.events()).isEmpty();
        assertThat(r.partial()).isTrue();           // Yahoo was needed and failed
        assertThat(finnhub.calls).hasValue(1);

        // Served from the short-TTL cache, not re-fetched.
        svc.earnings("ZZTOP", FROM, TO);
        assertThat(finnhub.calls).hasValue(1);

        // ...but only for 10 minutes, not 6 hours.
        clock.set(600_001L);
        svc.earnings("ZZTOP", FROM, TO);
        assertThat(finnhub.calls).hasValue(2);
    }

    @Test void mergesAcrossAllHealthyProvidersInsteadOfFirstSuccess() {
        // THE anti-regression for D3: this is exactly what first-success chains cannot do.
        // If finnhub answering ZZTOP had silently blocked yahoo, yahoo's QQTEST event and its
        // epsActual fill-in for ZZTOP would both be missing from the result.
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of(
                new EarningsEvent("ZZTOP", LocalDate.parse("2026-08-01"),
                        new BigDecimal("1.0"), null, null, null, null)));
        var yahoo = ok("yahoo", EarningsCoverage.FULL_WINDOW, List.of(
                new EarningsEvent("ZZTOP", LocalDate.parse("2026-08-01"),
                        null, new BigDecimal("1.1"), null, null, null),
                new EarningsEvent("QQTEST", LocalDate.parse("2026-08-02"),
                        new BigDecimal("2.0"), null, null, null, null)));
        var svc = service(List.of(finnhub, yahoo));

        var r = svc.earningsWindow(FROM, TO);

        assertThat(finnhub.calls).hasValue(1);
        assertThat(yahoo.calls).hasValue(1);          // both providers were needed and both ran
        assertThat(r.partial()).isFalse();
        assertThat(r.events()).extracting(EarningsEvent::symbol)
                .containsExactlyInAnyOrder("ZZTOP", "QQTEST");
        var zztop = r.events().stream().filter(e -> e.symbol().equals("ZZTOP")).findFirst().orElseThrow();
        assertThat(zztop.epsActual()).isEqualByComparingTo("1.1");   // yahoo's field survived the merge
    }

    @Test void allProvidersFailingThrowsAndIsNotCached() {
        var finnhub = dead("finnhub", EarningsCoverage.FULL_WINDOW);
        var svc = service(List.of(finnhub));

        assertThatThrownBy(() -> svc.earnings("ZZTOP", FROM, TO))
                .isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> svc.earnings("ZZTOP", FROM, TO))
                .isInstanceOf(MarketDataException.class);

        assertThat(finnhub.calls).hasValue(2);       // nothing cached
    }

    @Test void futureOnlyProviderCannotVouchForAnAllPastWindow() {
        // THE round-2 critical: get_earnings_window defaults to an all-past window. A
        // future-only source returning empty must NOT count as "somebody answered".
        var finnhub = dead("finnhub", EarningsCoverage.FULL_WINDOW);
        var nasdaq = ok("nasdaq", EarningsCoverage.FUTURE_ONLY, List.of());
        var svc = service(List.of(finnhub, nasdaq));

        assertThatThrownBy(() -> svc.earningsWindow(TODAY.minusDays(30), TODAY.minusDays(1)))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void allProvidersCooledYieldsUnavailableNotCachedEmpty() {
        var finnhub = dead("finnhub", EarningsCoverage.FULL_WINDOW);
        var svc = service(List.of(finnhub));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> svc.earnings("ZZTOP", FROM, TO))
                    .isInstanceOf(MarketDataException.class);
        }
        int callsBeforeCooldown = finnhub.calls.get();

        assertThatThrownBy(() -> svc.earnings("QQTEST", FROM, TO))
                .isInstanceOf(MarketDataException.class);

        assertThat(finnhub.calls).hasValue(callsBeforeCooldown);   // skipped, not called
    }

    @Test void successAfterFailuresResetsTheCooldownCounter() {
        var flaky = new Fake("finnhub", EarningsCoverage.FULL_WINDOW,
                List.of(ev("ZZTOP", "2026-08-01")), false);
        var svc = service(List.of(flaky));

        assertThat(svc.earnings("ZZTOP", FROM, TO).partial()).isFalse();
        assertThat(flaky.calls).hasValue(1);
    }

    @Test void cacheKeysAreCaseNormalised() {
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of(ev("ZZTOP", "2026-08-01")));
        var svc = service(List.of(finnhub));

        svc.earnings("ZZTOP", FROM, TO);
        svc.earnings("zztop", FROM, TO);

        assertThat(finnhub.calls).hasValue(1);
    }

    @Test void completeResultEvictsAnEarlierPartialUnderTheSameKey() {
        // No skip rule exists yet in Task 5: yahoo is needed for every call, so a healthy
        // finnhub alongside a failing yahoo yields a partial (not complete) answer.
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of(ev("ZZTOP", "2026-08-01")));
        var yahoo = new Toggle("yahoo", EarningsCoverage.FULL_WINDOW, List.of(), true);
        var svc = service(List.of(finnhub, yahoo));

        var first = svc.earnings("ZZTOP", FROM, TO);
        assertThat(first.partial()).isTrue();

        // Still within the partial TTL: served from the partial cache, no re-fetch.
        svc.earnings("ZZTOP", FROM, TO);
        assertThat(finnhub.calls).hasValue(1);

        // Past the partial TTL, yahoo has recovered -> a complete answer now replaces it.
        yahoo.failing.set(false);
        clock.set(600_001L);
        var second = svc.earnings("ZZTOP", FROM, TO);
        assertThat(second.partial()).isFalse();
        assertThat(finnhub.calls).hasValue(2);

        // The complete answer sticks even once the (now moot) partial TTL would elapse again.
        clock.set(1_200_002L);
        var third = svc.earnings("ZZTOP", FROM, TO);
        assertThat(third.partial()).isFalse();
        assertThat(finnhub.calls).hasValue(2);        // served from the complete cache, not re-fetched
    }

    @Test void windowAndSymbolCachesAreSeparateFamilies() {
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of(ev("ZZTOP", "2026-08-01")));
        var svc = service(List.of(finnhub));

        svc.earnings("ZZTOP", FROM, TO);
        svc.earningsWindow(FROM, TO);

        assertThat(finnhub.calls).hasValue(2);
    }

    // ---- T6 healthy-path rule ----------------------------------------------------
    // These two restore coverage that Task 5 had to strip out of this file (the skip rule and
    // the Yahoo page cache did not exist yet) and re-adds it now that they do.

    @Test void yahooSkippedByHealthyPathRuleCountsAsCompleteAndCachesLong() {
        // Phase 1 (finnhub) already covers the ticker, so the real Yahoo provider must never
        // be consulted at all -- not even a page-cache read.
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of(ev("ZZTOP", "2026-08-01")));
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            server.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                    .willReturn(okJson("""
                            {"rows":[{"ticker":"ZZTOP","startdatetime":"2026-08-01T12:00:00.000Z",
                                      "epsestimate":"1.25","epsactual":null,"epssurprisepct":null}]}""")));
            var yahoo = new YahooEarningsProvider(
                    "http://localhost:" + server.port(), "agora-test", 2000L, 3600L, clock::get);
            var svc = service(List.of(finnhub, yahoo));

            var r = svc.earnings("ZZTOP", FROM, TO);

            assertThat(r.partial()).isFalse();
            assertThat(r.events()).hasSize(1);
            assertThat(server.getAllServeEvents()).isEmpty();   // yahoo was never consulted

            // Complete results cache under the LONG TTL: still served, unre-fetched, past the
            // point where the short partial TTL (600s) would already have expired.
            clock.set(600_001L);
            var r2 = svc.earnings("ZZTOP", FROM, TO);
            assertThat(r2.partial()).isFalse();
            assertThat(finnhub.calls).hasValue(1);
            assertThat(server.getAllServeEvents()).isEmpty();
        } finally {
            server.stop();
        }
    }

    @Test void yahooPageCacheMissMakesResultPartialAndCachesShort() {
        // Phase 1 (finnhub) has nothing for the ticker, and Yahoo's page cache is cold, so the
        // result must come back partial rather than blocking on the crawl.
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of());
        // Unroutable base URL: window() must return empty synchronously regardless of whether
        // the background warm eventually succeeds or fails.
        var yahoo = new YahooEarningsProvider(
                "http://localhost:1", "agora-test", 2000L, 3600L, clock::get);
        var svc = service(List.of(finnhub, yahoo));

        var r = svc.earnings("ZZTOP", FROM, TO);

        assertThat(r.partial()).isTrue();
        assertThat(r.events()).isEmpty();

        // Cached under the SHORT (partial) TTL: past it, finnhub is re-queried.
        clock.set(600_001L);
        svc.earnings("ZZTOP", FROM, TO);
        assertThat(finnhub.calls).hasValue(2);
    }

    // ---- T6 fix round 1: the page-present branch (EarningsService.java:163-170) ---------------
    // Neither test above reaches this branch: the skip test never calls yahoo.window() at all,
    // and the miss test only exercises the "page absent" else-branch. These two seed the page
    // cache directly (as the async warm would) and go through EarningsService so the merge, the
    // anySuccess deviation and the resulting completeness/TTL are pinned down for real.

    @Test void yahooPageCacheHitMergesIntoResultAndCountsAsComplete() {
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of());   // nothing for ZZTOP
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            server.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                    .willReturn(okJson("""
                            {"rows":[{"ticker":"ZZTOP","startdatetime":"2026-08-01T12:00:00.000Z",
                                      "epsestimate":"1.25","epsactual":null,"epssurprisepct":null}]}""")));
            var yahoo = new YahooEarningsProvider(
                    "http://localhost:" + server.port(), "agora-test", 2000L, 3600L, clock::get);

            // Seed Yahoo's page cache directly, exactly as the async warm would populate it.
            yahoo.window(FROM, TO);
            await().atMost(Duration.ofSeconds(5)).until(() -> yahoo.window(FROM, TO).isPresent());

            var svc = service(List.of(finnhub, yahoo));
            var r = svc.earnings("ZZTOP", FROM, TO);

            // Yahoo's cache genuinely answered: this is the page-present merge branch, not the
            // cache-miss "degraded" else-branch.
            assertThat(r.partial()).isFalse();
            assertThat(r.events()).hasSize(1);
            assertThat(r.events().get(0).symbol()).isEqualTo("ZZTOP");

            // Complete results cache under the LONG TTL: still served, unre-fetched, past the
            // point where the short partial TTL (600s) would already have expired.
            clock.set(600_001L);
            var r2 = svc.earnings("ZZTOP", FROM, TO);
            assertThat(r2.partial()).isFalse();
            assertThat(finnhub.calls).hasValue(1);
        } finally {
            server.stop();
        }
    }

    @Test void yahooPageCacheHitFilteredEmptyForRequestedTickerStillCountsAsComplete() {
        // The deviation's edge case: Yahoo's page is present (a real answer), but it has nothing
        // for the requested ticker once filtered. That must read as "Yahoo confirmed nothing is
        // scheduled", i.e. complete with empty events -- not partial, and not mistaken for a miss.
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of());   // nothing for ZZTOP
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            server.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                    .willReturn(okJson("""
                            {"rows":[{"ticker":"QQTEST","startdatetime":"2026-08-01T12:00:00.000Z",
                                      "epsestimate":"1.25","epsactual":null,"epssurprisepct":null}]}""")));
            var yahoo = new YahooEarningsProvider(
                    "http://localhost:" + server.port(), "agora-test", 2000L, 3600L, clock::get);

            yahoo.window(FROM, TO);
            await().atMost(Duration.ofSeconds(5)).until(() -> yahoo.window(FROM, TO).isPresent());

            var svc = service(List.of(finnhub, yahoo));
            var r = svc.earnings("ZZTOP", FROM, TO);

            assertThat(r.partial()).isFalse();
            assertThat(r.events()).isEmpty();
        } finally {
            server.stop();
        }
    }

    // ---- T6 fix round 1: Yahoo's cooldown was previously permanently inert --------------------
    // Yahoo never enters the phase-1 fan-out, so nothing called cooldown.recordFailure/Success
    // for it; a chronically dead Yahoo was re-crawled (up to MAX_PAGES requests) on every single
    // cache-miss request forever. These two prove the async warm's outcome now feeds the shared
    // cooldown, and that the cooldown genuinely gates re-warming.

    /**
     * A {@link YahooEarningsProvider} that counts (a) every {@code window(...)} invocation and
     * (b) every completed warm outcome (success or failure), independent of the underlying HTTP
     * transport.
     *
     * <p>Two distinct real-call-count hazards make {@code server.getAllServeEvents()} the wrong
     * signal for either purpose:
     *
     * <ul>
     *   <li>{@code EarningsService} supplies its own {@code cooldown.recordSuccess/recordFailure}
     *       callbacks to {@code window(...)}; those run asynchronously on the warm thread,
     *       strictly after the HTTP call completes. Waiting for the serve-event count to grow
     *       races ahead of {@code cooldown.recordFailure(yahoo)} actually having run -- {@code
     *       warmOutcomes} (incremented from inside the callback, after it runs) is the correct
     *       synchronisation point instead.</li>
     *   <li>The JDK {@code HttpClient} used under {@code RestClient} can silently retry a GET at
     *       the transport level (e.g. a stale pooled connection race) without that retry ever
     *       reaching {@code ProviderCallLogger} or the caller -- observed directly: a run where
     *       WireMock's serve-event journal held 5 entries while {@code window(...)} had provably
     *       been invoked exactly 3 times. So "no new call was made" must be asserted against
     *       {@code windowCalls} (one increment per logical {@code window(...)} invocation), not
     *       against the WireMock journal, which counts physical connections/retries Yahoo's own
     *       cooldown gate has no way to see or control.</li>
     * </ul>
     */
    private static final class InstrumentedYahoo extends YahooEarningsProvider {
        final AtomicInteger windowCalls = new AtomicInteger();
        final AtomicInteger warmOutcomes = new AtomicInteger();

        InstrumentedYahoo(String baseUrl, String userAgent, long timeoutMs, long ttlSeconds,
                          java.util.function.LongSupplier now) {
            super(baseUrl, userAgent, timeoutMs, ttlSeconds, now);
        }

        @Override
        public java.util.Optional<List<EarningsEvent>> window(LocalDate from, LocalDate to,
                                                                Runnable onWarmSuccess, Runnable onWarmFailure) {
            windowCalls.incrementAndGet();
            return super.window(from, to,
                    () -> { onWarmSuccess.run(); warmOutcomes.incrementAndGet(); },
                    () -> { onWarmFailure.run(); warmOutcomes.incrementAndGet(); });
        }
    }

    @Test void repeatedlyFailingYahooWarmStopsBeingRetriedOnceCooldownTrips() {
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of());
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            server.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                    .willReturn(aResponse().withStatus(500)));
            var yahoo = new InstrumentedYahoo(
                    "http://localhost:" + server.port(), "agora-test", 2000L, 3600L, clock::get);
            // A short partial TTL (100s), deliberately decoupled from the 600s cooldown window,
            // so bypassing the partial cache between retries cannot also brush past cooldown.
            var svc = new EarningsService(List.of(finnhub, yahoo), 21600L, 100L, 3, 600_000L,
                    7000L, clock::get, () -> TODAY);

            // Three consecutive failed warms trip the cooldown (threshold=3). Wait on the warm
            // OUTCOME (i.e. cooldown.recordFailure(yahoo) has actually run), not on the HTTP call
            // landing -- those are two different moments and the test must not race ahead of it.
            for (int i = 1; i <= 3; i++) {
                svc.earnings("ZZTOP", FROM, TO);
                int expected = i;
                await().atMost(Duration.ofSeconds(5))
                        .until(() -> yahoo.warmOutcomes.get() >= expected);
                clock.addAndGet(100_001L);   // past the partial TTL, forcing a fresh attempt next time
            }
            assertThat(yahoo.windowCalls).hasValue(3);
            assertThat(yahoo.warmOutcomes).hasValue(3);

            // Cooldown is now tripped -- confirmed above by the 3rd recorded outcome, not merely
            // the 3rd HTTP call. The gate (!cooldown.isCooled(yahoo)) is checked synchronously
            // before window() is ever called, so this holds immediately; the `.during(...)` here
            // only proves it keeps holding, not that it needs time to become true.
            //
            // Asserted against yahoo.windowCalls, NOT server.getAllServeEvents(): the JDK
            // HttpClient underneath RestClient can silently retry a GET at the transport level
            // (a stale pooled connection race), which inflates WireMock's serve-event journal
            // without a second window() invocation ever happening -- observed directly in a run
            // where the journal held 5 entries against exactly 3 real window() calls. windowCalls
            // is incremented once per logical invocation and is what the cooldown gate controls.
            await().atMost(Duration.ofSeconds(2)).during(Duration.ofMillis(300))
                    .untilAsserted(() -> {
                        svc.earnings("ZZTOP", FROM, TO);
                        assertThat(yahoo.windowCalls).hasValue(3);
                    });
        } finally {
            server.stop();
        }
    }

    @Test void yahooWarmResumesAfterCooldownWindowElapses() {
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of());
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            server.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                    .willReturn(aResponse().withStatus(500)));
            var yahoo = new InstrumentedYahoo(
                    "http://localhost:" + server.port(), "agora-test", 2000L, 3600L, clock::get);
            var svc = new EarningsService(List.of(finnhub, yahoo), 21600L, 100L, 3, 600_000L,
                    7000L, clock::get, () -> TODAY);

            // Same rationale as above: wait on the recorded outcome, not the raw HTTP call, so
            // cooldown.recordFailure(yahoo) has genuinely run by the time the loop advances.
            for (int i = 1; i <= 3; i++) {
                svc.earnings("ZZTOP", FROM, TO);
                int expected = i;
                await().atMost(Duration.ofSeconds(5))
                        .until(() -> yahoo.warmOutcomes.get() >= expected);
                clock.addAndGet(100_001L);
            }
            assertThat(yahoo.windowCalls).hasValue(3);
            assertThat(yahoo.warmOutcomes).hasValue(3);

            // Jump the injected clock past the 600s cooldown window (no sleeping) and let Yahoo
            // "recover" -- the next call must attempt a fresh warm instead of staying gated.
            // (Deliberately no intermediate still-cooled call here: because finnhub answers with
            // an empty-but-successful result, such a call would cache as COMPLETE for the full
            // 6h TTL and mask the resume this test is checking for.)
            clock.set(800_003L);
            server.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                    .willReturn(okJson("""
                            {"rows":[{"ticker":"ZZTOP","startdatetime":"2026-08-01T12:00:00.000Z",
                                      "epsestimate":"1.25","epsactual":null,"epssurprisepct":null}]}""")));

            svc.earnings("ZZTOP", FROM, TO);
            // Asserted against windowCalls (a fresh, logical warm attempt was made), not the
            // WireMock journal -- see the sibling test for why the journal is unreliable here.
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> yahoo.windowCalls.get() > 3);
        } finally {
            server.stop();
        }
    }
}
