package de.visterion.agora.fetch.split;

import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class SplitServiceTest {

    private static SplitEvent ev() { return new SplitEvent(LocalDate.parse("2024-06-10"), BigDecimal.ONE, BigDecimal.TEN); }

    private SplitService svc(List<SplitProvider> providers) {
        return new SplitService(providers, 21600_000L, System::currentTimeMillis);
    }

    @Test void firstNonEmptyWins_secondNotCalled() {
        AtomicInteger finnhubCalls = new AtomicInteger();
        SplitProvider alpaca = stub("alpaca", () -> List.of(ev()));
        SplitProvider finnhub = stub("finnhub", () -> { finnhubCalls.incrementAndGet(); return List.of(); });
        assertThat(svc(List.of(alpaca, finnhub)).splits("NVDA")).hasSize(1);
        assertThat(finnhubCalls.get()).isZero();
    }

    @Test void firstEmpty_fallsThroughToSecond() {
        SplitProvider alpaca = stub("alpaca", List::of);
        SplitProvider finnhub = stub("finnhub", () -> List.of(ev()));
        assertThat(svc(List.of(alpaca, finnhub)).splits("NVDA")).hasSize(1);
    }

    @Test void throwingProvider_isSkipped() {
        SplitProvider alpaca = stub("alpaca", () -> { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no key", null); });
        SplitProvider finnhub = stub("finnhub", () -> List.of(ev()));
        assertThat(svc(List.of(alpaca, finnhub)).splits("NVDA")).hasSize(1);
    }

    @Test void allEmpty_returnsEmpty() {
        assertThat(svc(List.of(stub("a", List::of), stub("b", List::of))).splits("NVDA")).isEmpty();
    }

    @Test void cached_secondCallDoesNotReinvoke() {
        AtomicInteger calls = new AtomicInteger();
        SplitProvider p = stub("a", () -> { calls.incrementAndGet(); return List.of(ev()); });
        SplitService s = svc(List.of(p));
        s.splits("NVDA"); s.splits("NVDA");
        assertThat(calls.get()).isEqualTo(1);
    }

    private static SplitProvider stub(String name, java.util.function.Supplier<List<SplitEvent>> body) {
        return new SplitProvider() {
            public String name() { return name; }
            public List<SplitEvent> splits(String symbol) { return body.get(); }
        };
    }
}
