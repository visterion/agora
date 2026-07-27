package de.visterion.agora.fetch.earnings;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

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
}
