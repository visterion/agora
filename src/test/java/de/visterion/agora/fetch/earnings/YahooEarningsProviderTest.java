package de.visterion.agora.fetch.earnings;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class YahooEarningsProviderTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private YahooEarningsProvider p() { return new YahooEarningsProvider(wm.baseUrl(), "TestAgent/1.0"); }

    @Test void nameIsYahoo() { assertThat(p().name()).isEqualTo("yahoo"); }

    @Test void filtersBySymbol() {
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .willReturn(okJson("""
                    {"rows":[
                      {"ticker":"AAPL","companyshortname":"Apple","startdatetime":"2025-05-01T20:00:00Z","epsactual":"1.5","epsestimate":"1.4","epssurprisepct":"7.1"},
                      {"ticker":"MSFT","companyshortname":"Microsoft","startdatetime":"2025-05-02T20:00:00Z","epsactual":"2.9","epsestimate":"2.8","epssurprisepct":"3.5"}
                    ]}
                    """)));
        List<EarningsEvent> ev = p().earnings("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"));
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0).symbol()).isEqualTo("AAPL");
        assertThat(ev.get(0).date()).isEqualTo(LocalDate.parse("2025-05-01"));
    }

    @Test void fetchFailureThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> p().earnings("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void marketWideReturnsAllRows() {
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .willReturn(okJson("{\"rows\":["
                        + "{\"ticker\":\"AAPL\",\"startdatetime\":\"2025-05-01T12:00:00Z\",\"epsactual\":1.5,\"epsestimate\":1.4},"
                        + "{\"ticker\":\"MSFT\",\"startdatetime\":\"2025-05-02T12:00:00Z\",\"epsactual\":2.1,\"epsestimate\":2.0}]}")));
        var out = p().earnings(null, LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-03"));
        assertThat(out).extracting(EarningsEvent::symbol).containsExactly("AAPL", "MSFT");
    }

    @Test void afterCloseEventDateUsesNewYorkTimeZoneNotUtc() {
        // 2025-05-02T00:15:00Z is 2025-05-01 20:15 EDT (after-close report, still 05-01 in NY).
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .willReturn(okJson("""
                    {"rows":[
                      {"ticker":"AAPL","startdatetime":"2025-05-02T00:15:00Z","epsactual":"1.5","epsestimate":"1.4"}
                    ]}
                    """)));
        List<EarningsEvent> ev = p().earnings("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"));
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0).date()).isEqualTo(LocalDate.parse("2025-05-01"));
    }

    @Test void paginatesUntilSymbolFoundOnLaterPage() {
        StringBuilder page1 = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) page1.append(",");
            page1.append("{\"ticker\":\"SYM").append(i).append("\",\"startdatetime\":\"2025-05-01T20:00:00Z\"}");
        }
        page1.append("]}");
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .withQueryParam("offset", equalTo("0"))
                .willReturn(okJson(page1.toString())));
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .withQueryParam("offset", equalTo("100"))
                .willReturn(okJson("""
                    {"rows":[
                      {"ticker":"AAPL","startdatetime":"2025-05-03T20:00:00Z","epsactual":"1.5","epsestimate":"1.4"}
                    ]}
                    """)));
        List<EarningsEvent> ev = p().earnings("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"));
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0).symbol()).isEqualTo("AAPL");
        wm.verify(getRequestedFor(urlPathEqualTo("/v1/finance/calendar/earnings")).withQueryParam("offset", equalTo("100")));
    }

    @Test void pagingStopsAtCapWhenSymbolNeverFound() {
        StringBuilder fullPage = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) fullPage.append(",");
            fullPage.append("{\"ticker\":\"SYM").append(i).append("\",\"startdatetime\":\"2025-05-01T20:00:00Z\"}");
        }
        fullPage.append("]}");
        wm.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings")).willReturn(okJson(fullPage.toString())));
        List<EarningsEvent> ev = p().earnings("MISSING", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"));
        assertThat(ev).isEmpty();
        wm.verify(10, getRequestedFor(urlPathEqualTo("/v1/finance/calendar/earnings")));
    }
}
