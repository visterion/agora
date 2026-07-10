package de.visterion.agora.fetch.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class EdgarServiceCompanyFactsTest {
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

    @Test void parsesAllConceptsInOneFetch() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyfacts/CIK0000320193.json"))
                .willReturn(okJson("""
                    {"cik":320193,"facts":{"us-gaap":{
                      "Assets":{"units":{"USD":[{"end":"2023-09-30","val":352583000000,"fy":2023,"fp":"FY","form":"10-K","filed":"2023-11-03"}]}},
                      "NetIncomeLoss":{"units":{"USD":[{"start":"2022-10-01","end":"2023-09-30","val":96995000000,"fy":2023,"fp":"FY","form":"10-K","filed":"2023-11-03"}]}}
                    }}}
                    """)));
        EdgarService.CompanyFacts facts = svc().companyFacts("AAPL", null);

        EdgarService.ConceptSeries assets = facts.series("Assets");
        assertThat(assets.datapoints()).hasSize(1);
        ConceptDatapoint assetsPoint = assets.datapoints().get(0);
        assertThat(assetsPoint.value()).isEqualByComparingTo("352583000000");
        assertThat(assetsPoint.periodEnd()).isEqualTo(java.time.LocalDate.parse("2023-09-30"));
        assertThat(assetsPoint.periodStart()).isNull();

        EdgarService.ConceptSeries netIncome = facts.series("NetIncomeLoss");
        assertThat(netIncome.datapoints()).hasSize(1);
        ConceptDatapoint netIncomePoint = netIncome.datapoints().get(0);
        assertThat(netIncomePoint.periodStart()).isEqualTo(java.time.LocalDate.parse("2022-10-01"));
        assertThat(netIncomePoint.periodEnd()).isEqualTo(java.time.LocalDate.parse("2023-09-30"));

        assertThat(facts.series("Missing").datapoints()).isEmpty();
    }

    @Test void unreachableEdgarYieldsEmptyFacts() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyfacts/CIK0000320193.json"))
                .willReturn(aResponse().withStatus(404)));
        EdgarService.CompanyFacts facts = svc().companyFacts("AAPL", null);
        assertThat(facts.isEmpty()).isTrue();
        assertThat(facts.series("Assets").datapoints()).isEmpty();
    }

    @Test void serverErrorThrowsAndNothingCached() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyfacts/CIK0000320193.json"))
                .willReturn(aResponse().withStatus(500)));
        EdgarService svc = svc();
        assertThatThrownBy(() -> svc.companyFacts("AAPL", null))
                .isInstanceOf(de.visterion.agora.data.MarketDataException.class);

        // Reconfigure to succeed; a subsequent call must re-hit upstream (nothing was cached on failure).
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyfacts/CIK0000320193.json"))
                .willReturn(okJson("""
                    {"cik":320193,"facts":{"us-gaap":{
                      "Assets":{"units":{"USD":[{"end":"2023-09-30","val":352583000000,"fy":2023,"fp":"FY","form":"10-K","filed":"2023-11-03"}]}}
                    }}}
                    """)));
        EdgarService.CompanyFacts facts = svc.companyFacts("AAPL", null);
        assertThat(facts.isEmpty()).isFalse();
        assertThat(facts.series("Assets").datapoints()).hasSize(1);
    }
}
