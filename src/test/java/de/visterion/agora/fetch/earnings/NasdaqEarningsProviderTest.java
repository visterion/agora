package de.visterion.agora.fetch.earnings;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class NasdaqEarningsProviderTest {

    private WireMockServer server;
    private final AtomicLong now = new AtomicLong(0L);
    private static final LocalDate TODAY = LocalDate.parse("2026-07-27");

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

        p.earnings("ZZTOP", TODAY, TODAY.plusDays(10));

        // Nearest-to-today first: exactly the cap many days, starting at today.
        server.verify(2, getRequestedFor(urlPathEqualTo("/api/calendar/earnings")));
        server.verify(1, getRequestedFor(urlPathEqualTo("/api/calendar/earnings"))
                .withQueryParam("date", equalTo("2026-07-27")));
        assertThat(p.lastCallTruncated()).isTrue();
    }

    @Test void cachedDaysDoNotCountAgainstTheCap() {
        server.stubFor(get(urlPathEqualTo("/api/calendar/earnings"))
                .willReturn(okJson(EMPTY_BODY)));
        var p = provider(2);

        p.earnings("ZZTOP", TODAY, TODAY.plusDays(1));       // fetches 2 days, fills cache
        p.earnings("ZZTOP", TODAY, TODAY.plusDays(3));       // 2 cached + 2 fresh allowed

        server.verify(4, getRequestedFor(urlPathEqualTo("/api/calendar/earnings")));
        assertThat(p.lastCallTruncated()).isFalse();
    }
}
