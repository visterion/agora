package de.visterion.agora.fetch.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class EdgarServiceEpsTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private EdgarService svc() {
        EdgarCikResolver cik = new EdgarCikResolver(RestClient.builder().baseUrl(wm.baseUrl()).build()) {
            @Override public Optional<String> cik(String t) { return Optional.of("0000320193"); }
        };
        return new EdgarService(RestClient.builder().baseUrl(wm.baseUrl()).build(), cik, 3600L, System::currentTimeMillis);
    }

    @Test void parsesDilutedEps() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareDiluted.json"))
                .willReturn(okJson("""
                    {"units":{"USD/shares":[
                      {"start":"2025-01-01","end":"2025-03-31","val":2.40,"fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-05-01"}
                    ]}}
                    """)));
        List<EpsPoint> eps = svc().epsHistory("AAPL", null);
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0).value()).isEqualByComparingTo("2.40");
        assertThat(eps.get(0).fiscalPeriod()).isEqualTo("Q1");
    }

    @Test void fallsBackToBasicWhenDilutedEmpty() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareDiluted.json"))
                .willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareBasic.json"))
                .willReturn(okJson("""
                    {"units":{"USD/shares":[
                      {"start":"2025-01-01","end":"2025-03-31","val":2.55,"fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-05-01"}
                    ]}}
                    """)));
        List<EpsPoint> eps = svc().epsHistory("AAPL", null);
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0).value()).isEqualByComparingTo("2.55");
    }

    @Test void dedupPrefersQuarterlyOverYtdForSamePeriodEnd() {
        // Same period-end (2025-06-30), two facts: a 3-month (quarterly) one and a 6-month (YTD) one.
        // The YTD row is emitted LAST in the JSON to prove the winner is chosen by duration, not by order.
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareDiluted.json"))
                .willReturn(okJson("""
                    {"units":{"USD/shares":[
                      {"start":"2025-04-01","end":"2025-06-30","val":2.60,"fy":2025,"fp":"Q2","form":"10-Q","filed":"2025-08-01"},
                      {"start":"2025-01-01","end":"2025-06-30","val":5.00,"fy":2025,"fp":"Q2","form":"10-Q","filed":"2025-08-01"}
                    ]}}
                    """)));
        List<EpsPoint> eps = svc().epsHistory("AAPL", null);
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0).periodStart().toString()).isEqualTo("2025-04-01");
        assertThat(eps.get(0).value()).isEqualByComparingTo("2.60");
    }

    @Test void fallsBackToBasicWhenDilutedHasOnlyAnnualFacts() {
        // Diluted concept has ONLY an annual/YTD fact (raw non-empty, but zero quarterly facts) →
        // must fall back to Basic which carries a genuine quarterly fact.
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareDiluted.json"))
                .willReturn(okJson("""
                    {"units":{"USD/shares":[
                      {"start":"2025-01-01","end":"2025-12-31","val":10.00,"fy":2025,"fp":"FY","form":"10-K","filed":"2026-02-01"}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareBasic.json"))
                .willReturn(okJson("""
                    {"units":{"USD/shares":[
                      {"start":"2025-01-01","end":"2025-03-31","val":2.55,"fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-05-01"}
                    ]}}
                    """)));
        List<EpsPoint> eps = svc().epsHistory("AAPL", null);
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0).value()).isEqualByComparingTo("2.55");
    }

    @Test void serverErrorThrowsAndNothingCached() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareDiluted.json"))
                .willReturn(aResponse().withStatus(500)));
        EdgarService svc = svc();
        assertThatThrownBy(() -> svc.epsHistory("AAPL", null)).isInstanceOf(MarketDataException.class);

        // Reconfigure to succeed; a subsequent call must re-hit upstream (nothing was cached on failure).
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareDiluted.json"))
                .willReturn(okJson("""
                    {"units":{"USD/shares":[
                      {"start":"2025-01-01","end":"2025-03-31","val":2.40,"fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-05-01"}
                    ]}}
                    """)));
        List<EpsPoint> eps = svc.epsHistory("AAPL", null);
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0).value()).isEqualByComparingTo("2.40");
    }

    @Test void tooManyRequestsThrows() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareDiluted.json"))
                .willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> svc().epsHistory("AAPL", null)).isInstanceOf(MarketDataException.class);
    }

    @Test void unknownSymbolThrowsNotFound() {
        EdgarCikResolver cik = new EdgarCikResolver(RestClient.builder().baseUrl(wm.baseUrl()).build()) {
            @Override public Optional<String> cik(String t) { return Optional.empty(); }
        };
        EdgarService s = new EdgarService(RestClient.builder().baseUrl(wm.baseUrl()).build(), cik, 3600L, System::currentTimeMillis);
        assertThatThrownBy(() -> s.epsHistory("ZZZZ", null))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }
}
