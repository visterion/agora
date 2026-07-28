package de.visterion.agora.fetch.earnings;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class NasdaqEarningsProviderTest {

    private WireMockServer server;
    private final AtomicLong now = new AtomicLong(0L);
    private static final LocalDate TODAY = LocalDate.parse("2026-07-27");

    /** For tests about the day cap, where the time bound must not interfere. */
    private static final long NO_BUDGET_LIMIT = Long.MAX_VALUE;

    private static final String DAY_BODY = """
            {"data":{"asOf":"Mon, Jul 27, 2026",
             "rows":[
               {"symbol":"ZZTOP","name":"Synthetic Holdings","time":"time-pre-market",
                "epsForecast":"$1.25","noOfEsts":"3","fiscalQuarterEnding":"Jun/2026"},
               {"symbol":"QQTEST","name":"Placeholder Industries","time":"time-after-hours",
                "epsForecast":"($0.40)","noOfEsts":"1","fiscalQuarterEnding":"Jun/2026"},
               {"symbol":"","name":"No Ticker Corp","epsForecast":"$9.99"}
             ]}}""";

    private static final String EMPTY_BODY = """
            {"data":{"asOf":"Tue, Jul 28, 2026","rows":null}}""";

    @BeforeEach void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach void stop() { server.stop(); }

    private NasdaqEarningsProvider provider(int dayCap) {
        return new NasdaqEarningsProvider("http://localhost:" + server.port(),
                "agora-test", 2000L, dayCap, 3600L, now::get, () -> TODAY);
    }

    @Test void parsesRowsAndSkipsBlankTickers() {
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .withQueryParam("date", equalTo("2026-07-27"))
                .willReturn(okJson(DAY_BODY)));

        List<EarningsEvent> out = provider(95).earnings("ZZTOP", TODAY, TODAY);

        assertThat(out).singleElement().satisfies(e -> {
            assertThat(e.symbol()).isEqualTo("ZZTOP");
            assertThat(e.date()).isEqualTo(TODAY);
            assertThat(e.epsEstimate()).isEqualByComparingTo("1.25");
            assertThat(e.epsActual()).isNull();   // Nasdaq has no actuals
        });
    }

    @Test void parsesParenthesisedNegativeForecast() {
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .willReturn(okJson(DAY_BODY)));

        List<EarningsEvent> out = provider(95).earnings("QQTEST", TODAY, TODAY);

        assertThat(out).singleElement()
                .satisfies(e -> assertThat(e.epsEstimate()).isEqualByComparingTo("-0.40"));
    }

    @Test void marketWideReturnsEveryValidRow() {
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .willReturn(okJson(DAY_BODY)));

        assertThat(provider(95).earnings(null, TODAY, TODAY))
                .extracting(EarningsEvent::symbol)
                .containsExactlyInAnyOrder("ZZTOP", "QQTEST");
    }

    @Test void skipsPastDaysEntirely() {
        // All-past window: the provider must issue no HTTP call at all.
        provider(95).earnings("ZZTOP", TODAY.minusDays(5), TODAY.minusDays(1));
        server.verify(0, getRequestedFor(urlPathEqualTo("/api/calendar/earnings")));
    }

    @Test void cachesEachDayOnceAcrossSymbolsAndWindows() {
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .willReturn(okJson(DAY_BODY)));
        var p = provider(95);

        p.earnings("ZZTOP", TODAY, TODAY);
        p.earnings("QQTEST", TODAY, TODAY);          // different symbol, same day
        p.earnings(null, TODAY, TODAY);              // market-wide, same day

        server.verify(1, getRequestedFor(urlPathEqualTo("/api/calendar/earnings"))
                .withQueryParam("date", equalTo("2026-07-27")));
    }

    @Test void nullRowsYieldEmptyNotError() {
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .willReturn(okJson(EMPTY_BODY)));

        assertThat(provider(95).earnings("ZZTOP", TODAY, TODAY)).isEmpty();
    }

    @Test void httpErrorThrowsUnavailable() {
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> provider(95).earnings("ZZTOP", TODAY, TODAY))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("nasdaq");
    }

    @Test void dayCapLimitsFetchesAndReportsTruncation() {
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .willReturn(okJson(EMPTY_BODY)));
        var p = provider(2);

        var answer = p.earnings("ZZTOP", TODAY, TODAY.plusDays(10), NO_BUDGET_LIMIT);

        // Nearest-to-today first: exactly the cap many days, starting at today.
        server.verify(2, getRequestedFor(urlPathEqualTo("/api/calendar/earnings")));
        server.verify(1, getRequestedFor(urlPathEqualTo("/api/calendar/earnings"))
                .withQueryParam("date", equalTo("2026-07-27")));
        assertThat(answer.truncated()).isTrue();
    }

    @Test void cachedDaysDoNotCountAgainstTheCap() {
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .willReturn(okJson(EMPTY_BODY)));
        var p = provider(2);

        p.earnings("ZZTOP", TODAY, TODAY.plusDays(1), NO_BUDGET_LIMIT);   // fetches 2 days, fills cache
        var answer = p.earnings("ZZTOP", TODAY, TODAY.plusDays(3), NO_BUDGET_LIMIT);  // 2 cached + 2 fresh

        server.verify(4, getRequestedFor(urlPathEqualTo("/api/calendar/earnings")));
        assertThat(answer.truncated()).isFalse();
    }

    // ---- I4: the day loop is time-aware, not only cap-aware ----------------------------------

    /**
     * The injected clock advances a fixed step on every read, so "elapsed time" is entirely
     * logical — no sleeping and no wall-clock dependency. The provider stops before starting a
     * day whose worst case (connect + read timeout) could outlast the budget.
     */
    @Test void budgetStopsTheDayLoopBeforeTheCapIsReached() {
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .willReturn(okJson(EMPTY_BODY)));
        var steppingClock = new AtomicLong(0L);
        var p = new NasdaqEarningsProvider("http://localhost:" + server.port(), "agora-test",
                2000L, 95, 3600L, () -> steppingClock.getAndAdd(200L), () -> TODAY,
                5_000L /* worst case for one day */);

        // 180-day window, cap 95, budget 9000: neither the window nor the cap is the binding
        // constraint here -- the budget is.
        var answer = p.earnings("ZZTOP", TODAY, TODAY.plusDays(179), 9_000L);

        assertThat(answer.truncated())
                .as("stopping early for time is still an incomplete view of the window")
                .isTrue();
        int fetched = server.getAllServeEvents().size();
        assertThat(fetched).isGreaterThan(0);      // it made real progress...
        assertThat(fetched).isLessThan(95);        // ...but the budget, not the cap, stopped it
    }

    @Test void aGenerousBudgetDoesNotTruncate() {
        // Same stepping clock, same window: with room in the budget the loop runs to the end,
        // proving the previous test's truncation came from the budget and nothing else.
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .willReturn(okJson(EMPTY_BODY)));
        var steppingClock = new AtomicLong(0L);
        var p = new NasdaqEarningsProvider("http://localhost:" + server.port(), "agora-test",
                2000L, 95, 3600L, () -> steppingClock.getAndAdd(200L), () -> TODAY, 5_000L);

        var answer = p.earnings("ZZTOP", TODAY, TODAY.plusDays(9), 1_000_000L);

        assertThat(answer.truncated()).isFalse();
        server.verify(10, getRequestedFor(urlPathEqualTo("/api/calendar/earnings")));
    }

    /**
     * I2: truncation travels with the call's data, so concurrent calls for different windows
     * cannot contaminate each other. The previous shape — a {@code volatile boolean
     * lastCallTruncated} field on this Spring singleton — was read after the fact by the caller,
     * so whichever call finished last decided what every concurrent caller saw.
     *
     * <p>Both threads are released from one barrier and the stub answers with a fixed delay, so
     * their fetch phases genuinely overlap; the assertions themselves do not depend on the
     * interleaving, so there is no timing race to lose.
     */
    @Test void concurrentCallsForDifferentWindowsDoNotContaminateEachOther() throws Exception {
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .willReturn(okJson(EMPTY_BODY).withFixedDelay(60)));
        var p = provider(3);
        var barrier = new CyclicBarrier(2);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // Wide: 30 days against a cap of 3 -> must truncate.
            Future<ProviderEarnings> wide = pool.submit(() -> {
                barrier.await();
                return p.earnings("ZZTOP", TODAY.plusDays(100), TODAY.plusDays(130), NO_BUDGET_LIMIT);
            });
            // Narrow: 2 days against the same cap -> must not truncate.
            Future<ProviderEarnings> narrow = pool.submit(() -> {
                barrier.await();
                return p.earnings("ZZTOP", TODAY.plusDays(200), TODAY.plusDays(201), NO_BUDGET_LIMIT);
            });

            assertThat(wide.get(30, TimeUnit.SECONDS).truncated()).isTrue();
            assertThat(narrow.get(30, TimeUnit.SECONDS).truncated())
                    .as("a narrow window's answer must never inherit a concurrent wide window's truncation")
                    .isFalse();
        }
    }
}
