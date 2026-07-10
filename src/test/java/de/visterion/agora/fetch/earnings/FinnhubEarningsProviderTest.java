package de.visterion.agora.fetch.earnings;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class FinnhubEarningsProviderTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private FinnhubEarningsProvider p(String key) { return new FinnhubEarningsProvider(wm.baseUrl(), key); }

    @Test void nameIsFinnhub() { assertThat(p("k").name()).isEqualTo("finnhub"); }

    @Test void parsesEarningsRows() {
        wm.stubFor(get(urlPathEqualTo("/calendar/earnings"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .withQueryParam("token", equalTo("k"))
                .willReturn(okJson("""
                    {"earningsCalendar":[
                      {"symbol":"AAPL","date":"2025-05-01","epsActual":1.5,"epsEstimate":1.4,"revenueActual":95000,"revenueEstimate":94000}
                    ]}
                    """)));
        List<EarningsEvent> ev = p("k").earnings("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"));
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0).symbol()).isEqualTo("AAPL");
        assertThat(ev.get(0).epsActual()).isEqualByComparingTo("1.5");
        assertThat(ev.get(0).epsSurprisePct()).isNotNull();
    }

    @Test void blankKeyThrowsUnavailable() {
        assertThatThrownBy(() -> p("").earnings("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void httpErrorThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/calendar/earnings")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> p("k").earnings("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void marketWideOmitsSymbolParamAndKeepsPerRowSymbols() {
        wm.stubFor(get(urlPathEqualTo("/calendar/earnings"))
                .withQueryParam("symbol", absent())
                .willReturn(okJson("{\"earningsCalendar\":["
                        + "{\"symbol\":\"AAPL\",\"date\":\"2025-05-01\",\"epsActual\":1.5,\"epsEstimate\":1.4},"
                        + "{\"symbol\":\"MSFT\",\"date\":\"2025-05-02\",\"epsActual\":2.1,\"epsEstimate\":2.0}]}")));
        var out = p("k").earnings(null, LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-03"));
        assertThat(out).extracting(EarningsEvent::symbol).containsExactly("AAPL", "MSFT");
    }

    @Test void marketWideSkipsSymbolLessRowsWithoutNpe() {
        wm.stubFor(get(urlPathEqualTo("/calendar/earnings"))
                .withQueryParam("symbol", absent())
                .willReturn(okJson("{\"earningsCalendar\":["
                        + "{\"date\":\"2025-05-01\",\"epsActual\":1.5,\"epsEstimate\":1.4},"
                        + "{\"symbol\":\"AAPL\",\"date\":\"2025-05-02\",\"epsActual\":2.1,\"epsEstimate\":2.0}]}")));
        var out = p("k").earnings(null, LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-03"));
        assertThat(out).extracting(EarningsEvent::symbol).containsExactly("AAPL");
    }

    @Test
    void slowResponseFailsFastAsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/calendar/earnings"))
                .willReturn(okJson("{\"earningsCalendar\":[]}").withFixedDelay(3_000)));
        var fast = new FinnhubEarningsProvider(wm.baseUrl(), "k", 250L);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> fast.earnings("AAPL", LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-10")))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
        assertThat((System.nanoTime() - t0) / 1_000_000L).isLessThan(2_500L);
    }
}
