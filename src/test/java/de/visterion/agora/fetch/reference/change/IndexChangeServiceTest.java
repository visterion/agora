package de.visterion.agora.fetch.reference.change;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexChangeServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC);

    private static IndexChange add(String sym, LocalDate ann, LocalDate eff) {
        return new IndexChange(sym, "add", "sp500", ann, eff, "sp_press");
    }

    private record StubProvider(int order, List<IndexChange> changes) implements IndexChangeProvider {
        @Override public int order() { return order; }
        @Override public List<IndexChange> changes(String index) { return changes; }
    }

    @Test void aggregatesAcrossProvidersInOrder() {
        var a = new StubProvider(20, List.of(add("YYY", LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 20))));
        var b = new StubProvider(10, List.of(add("XXX", LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 15))));
        var svc = new IndexChangeService(List.of(a, b), CLOCK);

        // ordered by order(): b (10) before a (20)
        assertThat(svc.changes("sp500", 30)).extracting(IndexChange::symbol)
                .containsExactly("XXX", "YYY");
    }

    @Test void dedupsOnIndexSymbolActionEffectiveDate() {
        var dup = add("XXX", LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 15));
        // same natural key but lower-cased symbol + different source -> still a duplicate
        var dupCased = new IndexChange("xxx", "add", "sp500",
                LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 15), "other");
        var first = new StubProvider(10, List.of(dup));
        var second = new StubProvider(20, List.of(dupCased,
                add("ZZZ", LocalDate.of(2026, 7, 11), LocalDate.of(2026, 7, 25))));
        var svc = new IndexChangeService(List.of(first, second), CLOCK);

        List<IndexChange> out = svc.changes("sp500", 30);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).source()).isEqualTo("sp_press"); // first provider wins the dup
        assertThat(out).extracting(IndexChange::symbol).containsExactly("XXX", "ZZZ");
    }

    @Test void dropsChangesOlderThanLookback() {
        var recent = add("NEW", LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 20));
        var old = add("OLD", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10));
        var svc = new IndexChangeService(List.of(new StubProvider(10, List.of(recent, old))), CLOCK);

        // cutoff = 2026-07-12 - 30 = 2026-06-12; OLD (announced 2026-05-01) is dropped
        assertThat(svc.changes("sp500", 30)).extracting(IndexChange::symbol).containsExactly("NEW");
        // lookback <= 0 disables the cutoff
        assertThat(svc.changes("sp500", 0)).extracting(IndexChange::symbol).containsExactly("NEW", "OLD");
    }

    @Test void skipsThrowingProviderAndKeepsOthers() {
        IndexChangeProvider bad = new IndexChangeProvider() {
            @Override public int order() { return 5; }
            @Override public List<IndexChange> changes(String index) { throw new RuntimeException("boom"); }
        };
        var good = new StubProvider(10, List.of(add("XXX", LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 15))));
        var svc = new IndexChangeService(List.of(bad, good), CLOCK);

        assertThat(svc.changes("sp500", 30)).extracting(IndexChange::symbol).containsExactly("XXX");
    }

    @Test void emptyWhenNoProviders() {
        assertThat(new IndexChangeService(List.of(), CLOCK).changes("sp500", 30)).isEmpty();
    }
}
