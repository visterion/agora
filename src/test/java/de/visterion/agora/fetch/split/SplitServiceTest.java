package de.visterion.agora.fetch.split;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.finnhub.FinnhubClient;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class SplitServiceTest {

    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private static SplitEvent ev() { return new SplitEvent(LocalDate.parse("2024-06-10"), BigDecimal.ONE, BigDecimal.TEN); }

    private SplitService svc(List<SplitProvider> providers) {
        return new SplitService(providers, 21600_000L, System::currentTimeMillis);
    }

    @Test void bothProvidersConsulted_resultIsUnion() {
        // M-F11: alpaca and finnhub are BOTH consulted (not first-non-empty-wins), and their
        // split histories are merged so pre-2015 finnhub data isn't shadowed by alpaca's 2015+ cap.
        AtomicInteger finnhubCalls = new AtomicInteger();
        SplitEvent alpacaEvent = new SplitEvent(LocalDate.parse("2024-06-10"), BigDecimal.ONE, BigDecimal.TEN);
        SplitEvent finnhubOldEvent = new SplitEvent(LocalDate.parse("2000-03-01"), BigDecimal.ONE, BigDecimal.TWO);
        SplitProvider alpaca = stub("alpaca", () -> List.of(alpacaEvent));
        SplitProvider finnhub = stub("finnhub", () -> { finnhubCalls.incrementAndGet(); return List.of(finnhubOldEvent); });
        List<SplitEvent> merged = svc(List.of(alpaca, finnhub)).splits("NVDA");
        assertThat(finnhubCalls.get()).isEqualTo(1);
        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(SplitEvent::date)
                .containsExactly(LocalDate.parse("2000-03-01"), LocalDate.parse("2024-06-10"));
    }

    @Test void unionByDate_alpacaWinsConflicts() {
        SplitEvent alpacaEvent = new SplitEvent(LocalDate.parse("2024-06-10"), BigDecimal.ONE, BigDecimal.TEN);
        SplitEvent finnhubConflict = new SplitEvent(LocalDate.parse("2024-06-10"), BigDecimal.ONE, BigDecimal.valueOf(4));
        SplitProvider alpaca = stub("alpaca", () -> List.of(alpacaEvent));
        SplitProvider finnhub = stub("finnhub", () -> List.of(finnhubConflict));
        List<SplitEvent> merged = svc(List.of(alpaca, finnhub)).splits("NVDA");
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).toFactor()).isEqualByComparingTo("10"); // alpaca wins the conflict
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

    @Test void allProvidersThrow_propagatesAndIsNotCached() {
        AtomicInteger aCalls = new AtomicInteger();
        AtomicInteger bCalls = new AtomicInteger();
        SplitProvider a = stub("a", () -> { aCalls.incrementAndGet(); throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "a down", null); });
        SplitProvider b = stub("b", () -> { bCalls.incrementAndGet(); throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "b down", null); });
        SplitService s = svc(List.of(a, b));

        assertThatThrownBy(() -> s.splits("X")).isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> s.splits("X")).isInstanceOf(MarketDataException.class);

        assertThat(aCalls.get()).isEqualTo(2);
        assertThat(bCalls.get()).isEqualTo(2);
    }

    @Test void nonMarketDataExceptionFromOneProviderDoesNotAbortChain() {
        // M-D1: a provider throwing a plain RuntimeException (e.g. NPE) must not abort the
        // fallback chain — the next provider should still be consulted.
        SplitProvider broken = stub("broken", () -> { throw new NullPointerException("boom"); });
        SplitProvider finnhub = stub("finnhub", () -> List.of(ev()));
        assertThat(svc(List.of(broken, finnhub)).splits("NVDA")).hasSize(1);
    }

    @Test void oneAnsweredEmpty_oneThrew_returnsEmptyNotThrow() {
        SplitProvider a = stub("a", () -> { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "a down", null); });
        SplitProvider b = stub("b", List::of);
        assertThat(svc(List.of(a, b)).splits("X")).isEmpty();
    }

    @Test void cached_secondCallDoesNotReinvoke() {
        AtomicInteger calls = new AtomicInteger();
        SplitProvider p = stub("a", () -> { calls.incrementAndGet(); return List.of(ev()); });
        SplitService s = svc(List.of(p));
        s.splits("NVDA"); s.splits("NVDA");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void finnhubSplitsDisabled_serviceNeverCallsFinnhubOverHttp() {
        // 2026-08-08 sweep fix: agora.data.finnhub.splits-enabled=false must stop SplitService
        // from reaching Finnhub at all, not merely change what it returns — verified at the HTTP
        // interaction level (wm.verify), not just on resolve()'s result.
        FinnhubClient client = new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), "k");
        FinnhubSplitProvider finnhub = new FinnhubSplitProvider(client, false);
        SplitProvider alpaca = stub("alpaca", () -> { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "alpaca: no data", null); });

        assertThatThrownBy(() -> svc(List.of(alpaca, finnhub)).splits("VICI")).isInstanceOf(MarketDataException.class);
        wm.verify(0, getRequestedFor(urlPathEqualTo("/stock/split")));
    }

    @Test void finnhubSplitsEnabled_answerPassesThroughUnchanged() {
        // Same wiring with the switch on: Finnhub is called and its answer reaches resolve()
        // unchanged, matching today's behaviour.
        wm.stubFor(get(urlPathEqualTo("/stock/split"))
            .willReturn(okJson("[{\"symbol\":\"VICI\",\"date\":\"2024-06-10\",\"fromFactor\":1,\"toFactor\":10}]")));
        FinnhubClient client = new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), "k");
        FinnhubSplitProvider finnhub = new FinnhubSplitProvider(client, true);
        SplitProvider alpaca = stub("alpaca", () -> { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "alpaca: no data", null); });

        List<SplitEvent> result = svc(List.of(alpaca, finnhub)).splits("VICI");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).date()).isEqualTo(LocalDate.parse("2024-06-10"));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/stock/split")));
    }

    private static SplitProvider stub(String name, java.util.function.Supplier<List<SplitEvent>> body) {
        return new SplitProvider() {
            public String name() { return name; }
            public List<SplitEvent> splits(String symbol) { return body.get(); }
        };
    }
}
