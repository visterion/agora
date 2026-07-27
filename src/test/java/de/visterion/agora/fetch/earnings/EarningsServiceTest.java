package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    @Test void skippedUnnecessaryProviderStillCountsAsComplete() {
        // Finnhub covered the ticker, so Yahoo was never needed — that is not "partial".
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of(ev("ZZTOP", "2026-08-01")));
        var yahoo = ok("yahoo", EarningsCoverage.FULL_WINDOW, List.of());
        var svc = service(List.of(finnhub, yahoo));

        var r = svc.earnings("ZZTOP", FROM, TO);

        assertThat(r.events()).hasSize(1);
        assertThat(r.partial()).isFalse();
        assertThat(yahoo.calls).hasValue(0);

        clock.set(600_001L);                         // past the partial TTL
        svc.earnings("ZZTOP", FROM, TO);
        assertThat(finnhub.calls).hasValue(1);       // still cached: complete TTL is 6h
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
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of(ev("ZZTOP", "2026-08-01")));
        var yahoo = new Fake("yahoo", EarningsCoverage.FULL_WINDOW, List.of(), true);
        var svc = service(List.of(finnhub, yahoo));

        // Finnhub covers the ticker, so yahoo is never consulted -> complete straight away.
        assertThat(svc.earnings("ZZTOP", FROM, TO).partial()).isFalse();
        assertThat(svc.earnings("ZZTOP", FROM, TO).partial()).isFalse();
    }

    @Test void windowAndSymbolCachesAreSeparateFamilies() {
        var finnhub = ok("finnhub", EarningsCoverage.FULL_WINDOW, List.of(ev("ZZTOP", "2026-08-01")));
        var svc = service(List.of(finnhub));

        svc.earnings("ZZTOP", FROM, TO);
        svc.earningsWindow(FROM, TO);

        assertThat(finnhub.calls).hasValue(2);
    }
}
