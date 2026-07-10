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

    @Test void dedupTieBreaksByLatestFiledWhenDurationsEqual() {
        // Same period (start+end identical -> equal duration): an original 10-Q fact and a later
        // 10-Q/A restatement. The restated (later-filed) row is emitted FIRST in the JSON to prove
        // the winner is chosen by latest `filed`, not by JSON order.
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareDiluted.json"))
                .willReturn(okJson("""
                    {"units":{"USD/shares":[
                      {"start":"2025-01-01","end":"2025-03-31","val":2.45,"fy":2025,"fp":"Q1","form":"10-Q/A","filed":"2025-06-01"},
                      {"start":"2025-01-01","end":"2025-03-31","val":2.40,"fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-05-01"}
                    ]}}
                    """)));
        List<EpsPoint> eps = svc().epsHistory("AAPL", null);
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0).value()).isEqualByComparingTo("2.45");
        assertThat(eps.get(0).form()).isEqualTo("10-Q/A");
    }

    @Test void quarterlySeriesExcludesFyAndDerivesQ4() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareDiluted.json"))
                .willReturn(okJson("""
                    {"units":{"USD/shares":[
                      {"start":"2024-01-01","end":"2024-03-31","val":1.00,"fy":2024,"fp":"Q1","form":"10-Q","filed":"2024-05-01"},
                      {"start":"2024-04-01","end":"2024-06-30","val":1.10,"fy":2024,"fp":"Q2","form":"10-Q","filed":"2024-08-01"},
                      {"start":"2024-07-01","end":"2024-09-30","val":1.20,"fy":2024,"fp":"Q3","form":"10-Q","filed":"2024-11-01"},
                      {"start":"2024-01-01","end":"2024-12-31","val":4.50,"fy":2024,"fp":"FY","form":"10-K","filed":"2025-02-01"},
                      {"start":"2023-01-01","end":"2023-12-31","val":3.80,"fy":2023,"fp":"FY","form":"10-K","filed":"2024-02-01"}
                    ]}}
                    """)));
        List<EpsPoint> eps = svc().epsHistory("AAPL", null);
        // 2023 is FY-only (no Q1-Q3) -> omitted entirely. 2024 has Q1,Q2,Q3 + derived Q4.
        assertThat(eps).hasSize(4);
        assertThat(eps).noneMatch(p -> p.periodEnd().getYear() == 2023);
        EpsPoint q4 = eps.stream().filter(p -> "Q4".equals(p.fiscalPeriod())).findFirst().orElseThrow();
        assertThat(q4.derived()).isTrue();
        assertThat(q4.value()).isEqualByComparingTo("1.20"); // 4.50 - (1.00+1.10+1.20)
        assertThat(q4.periodEnd()).isEqualTo(java.time.LocalDate.parse("2024-12-31"));
        assertThat(eps.stream().filter(p -> !"Q4".equals(p.fiscalPeriod()))).allMatch(p -> !p.derived());
    }

    @Test void epsPathNeverMergesUnits_prefersUsdPerShares() {
        // A foreign filer reporting EPS in both EUR/shares and USD/shares for the same tag:
        // must select USD/shares only, never merge the two currencies.
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/EarningsPerShareDiluted.json"))
                .willReturn(okJson("""
                    {"units":{
                      "EUR/shares":[
                        {"start":"2025-01-01","end":"2025-03-31","val":9.99,"fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-05-01"}
                      ],
                      "USD/shares":[
                        {"start":"2025-01-01","end":"2025-03-31","val":2.40,"fy":2025,"fp":"Q1","form":"10-Q","filed":"2025-05-01"}
                      ]
                    }}
                    """)));
        List<EpsPoint> eps = svc().epsHistory("AAPL", null);
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0).value()).isEqualByComparingTo("2.40");
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
