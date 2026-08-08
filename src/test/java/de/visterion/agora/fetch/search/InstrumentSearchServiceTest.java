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
        List<SearchHit> many = List.of(hit("A"), hit("B"), hit("C"), hit("D"), hit("E"),
                                       hit("F"), hit("G"));
        InstrumentSearchService svc = serviceReturning(many);

        assertThat(svc.search("nokia", 2)).hasSize(2);
        assertThat(svc.search("nokia", 7)).hasSize(7);
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

    @Test void cooldownHealsOnTheFirstSuccessAfterItExpires() {
        AtomicInteger calls = new AtomicInteger();
        InstrumentSearchService svc = new InstrumentSearchService(
                (q, count) -> {
                    if (calls.incrementAndGet() <= 1) {
                        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "boom", null);
                    }
                    return List.of(hit("SYNA"));
                },
                600_000L, 1, 60_000L, clock::get);

        assertThatThrownBy(() -> svc.search("a", 10)).isInstanceOf(MarketDataException.class);
        clock.addAndGet(60_001L);

        assertThat(svc.search("b", 10)).hasSize(1);
        assertThat(svc.search("c", 10)).hasSize(1);
    }
}
