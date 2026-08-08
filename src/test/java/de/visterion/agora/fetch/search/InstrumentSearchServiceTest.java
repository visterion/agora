package de.visterion.agora.fetch.search;

import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstrumentSearchServiceTest {

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final AtomicInteger upstreamCalls = new AtomicInteger();

    private InstrumentSearchService serviceReturning(List<SearchHit> hits) {
        return new InstrumentSearchService(
                (q, count) -> { upstreamCalls.incrementAndGet(); return hits; },
                600_000L, 2, 60_000L, clock::get);
    }

    private static SearchHit hit(String symbol) {
        return new SearchHit(symbol, symbol + " Corp", "NYSE", "EQUITY");
    }

    @Test void normalisesTheCacheKeySoCasingAndPaddingShareOneUpstreamCall() {
        InstrumentSearchService svc = serviceReturning(List.of(hit("SYNA")));

        svc.search("  NoKiA ", 10);
        svc.search("nokia", 10);

        assertThat(upstreamCalls).hasValue(1);
    }

    @Test void aDifferentLimitMustNotBeServedTheTruncatedEntry() {
        // Real endpoint, measured: quotesCount=6 and quotesCount=10 return different result
        // sets for the same query. The stub mirrors that by sizing its answer off quotesCount,
        // so a cache key that drops quotesCount (query alone) would serve call 2 the 6-item
        // answer cached by call 1 and truncate it to 6, not 7 — failing the size assertion —
        // while also never reaching upstream a second time, failing the call-count assertion.
        InstrumentSearchService svc = new InstrumentSearchService(
                (q, quotesCount) -> {
                    upstreamCalls.incrementAndGet();
                    return java.util.stream.IntStream.range(0, quotesCount)
                            .mapToObj(i -> hit("S" + i))
                            .toList();
                },
                600_000L, 2, 60_000L, clock::get);

        assertThat(svc.search("nokia", 2)).hasSize(2);
        assertThat(svc.search("nokia", 7)).hasSize(7);
        assertThat(upstreamCalls).hasValue(2);
    }

    @Test void truncatesToLimit() {
        InstrumentSearchService svc = serviceReturning(List.of(hit("A"), hit("B"), hit("C")));

        assertThat(svc.search("x", 2)).extracting(SearchHit::symbol).containsExactly("A", "B");
    }

    @Test void refetchesAfterTtlExpiry() {
        InstrumentSearchService svc = serviceReturning(List.of(hit("SYNA")));

        svc.search("nokia", 10);
        clock.addAndGet(600_001L);
        svc.search("nokia", 10);

        assertThat(upstreamCalls).hasValue(2);
    }

    @Test void upstreamFailureIsNotCached() {
        InstrumentSearchService svc = new InstrumentSearchService(
                (q, count) -> { upstreamCalls.incrementAndGet();
                    throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "boom", null); },
                600_000L, 99, 60_000L, clock::get);

        assertThatThrownBy(() -> svc.search("nokia", 10)).isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> svc.search("nokia", 10)).isInstanceOf(MarketDataException.class);

        assertThat(upstreamCalls).hasValue(2);
    }

    @Test void armsCooldownAfterRepeatedFailuresAndThenRefusesWithoutCallingUpstream() {
        InstrumentSearchService svc = new InstrumentSearchService(
                (q, count) -> { upstreamCalls.incrementAndGet();
                    throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "boom", null); },
                600_000L, 2, 60_000L, clock::get);

        assertThatThrownBy(() -> svc.search("a", 10)).isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> svc.search("b", 10)).isInstanceOf(MarketDataException.class);
        int callsBefore = upstreamCalls.get();

        assertThatThrownBy(() -> svc.search("c", 10))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("cooling down");
        assertThat(upstreamCalls).hasValue(callsBefore);
    }

    @Test void aCachedQueryIsStillServedWhileCooledDown() {
        AtomicInteger calls = new AtomicInteger();
        InstrumentSearchService svc = new InstrumentSearchService(
                (q, count) -> {
                    if (calls.incrementAndGet() == 1) return List.of(hit("SYNA"));
                    throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "boom", null);
                },
                600_000L, 1, 60_000L, clock::get);

        assertThat(svc.search("nokia", 10)).hasSize(1);          // warms the cache
        assertThatThrownBy(() -> svc.search("other", 10))         // arms the cooldown
                .isInstanceOf(MarketDataException.class);

        assertThat(svc.search("nokia", 10)).hasSize(1);           // cache still served
    }

    @Test void recordSuccessResetsAFailureStreakBelowThreshold() {
        // threshold=2: fail once (streak=1, below threshold, not armed), succeed (must reset
        // the streak to 0), then fail once more. A correct reset leaves the streak at 1 after
        // that failure, still below threshold — so the 4th call must still reach upstream and
        // succeed. Without the reset, the streak would reach 2 on the second failure and arm
        // the cooldown, making the 4th call throw "cooling down" instead.
        AtomicInteger calls = new AtomicInteger();
        InstrumentSearchService svc = new InstrumentSearchService(
                (q, count) -> {
                    int n = calls.incrementAndGet();
                    if (n == 2 || n == 4) {
                        return List.of(hit("SYNA"));
                    }
                    throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "boom", null);
                },
                600_000L, 2, 60_000L, clock::get);

        assertThatThrownBy(() -> svc.search("a", 10)).isInstanceOf(MarketDataException.class); // failure, streak=1
        assertThat(svc.search("b", 10)).hasSize(1);                                            // success, resets streak
        assertThatThrownBy(() -> svc.search("c", 10)).isInstanceOf(MarketDataException.class);  // failure, streak=1

        assertThat(svc.search("d", 10)).hasSize(1);                                             // not cooled down
    }

    @Test void cooldownOpensAgainOnceTheWindowPasses() {
        // Complements recordSuccessResetsAFailureStreakBelowThreshold: that one proves a
        // sub-threshold streak resets and never arms. This one proves the other half of why
        // the cooldown is time-bounded rather than permanent — once armed, it must stop
        // blocking after cooldownMillis elapses, and the call that reopens it must actually
        // reach upstream again (not just stay permanently refused).
        AtomicInteger calls = new AtomicInteger();
        InstrumentSearchService svc = new InstrumentSearchService(
                (q, count) -> {
                    if (calls.incrementAndGet() <= 2) {
                        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "boom", null);
                    }
                    return List.of(hit("SYNA"));
                },
                600_000L, 2, 60_000L, clock::get);

        assertThatThrownBy(() -> svc.search("a", 10)).isInstanceOf(MarketDataException.class); // failure, streak=1
        assertThatThrownBy(() -> svc.search("b", 10)).isInstanceOf(MarketDataException.class); // failure, streak=2 -> arms

        int callsWhileArmed = calls.get();
        assertThatThrownBy(() -> svc.search("c", 10))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("cooling down");
        assertThat(calls).hasValue(callsWhileArmed);                                           // upstream not reached

        clock.addAndGet(60_001L);                                                               // window passes

        assertThat(svc.search("d", 10)).hasSize(1);                                             // upstream reached again
        assertThat(calls).hasValue(callsWhileArmed + 1);
    }
}
