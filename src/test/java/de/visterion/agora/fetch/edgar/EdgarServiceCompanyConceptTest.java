package de.visterion.agora.fetch.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class EdgarServiceCompanyConceptTest {
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

    @Test void parsesConceptSeries() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/Assets.json"))
                .willReturn(okJson("""
                    {"units":{"USD":[
                      {"start":"2024-01-01","end":"2024-12-31","val":365000000000,"fy":2024,"fp":"FY","form":"10-K","filed":"2025-01-31"}
                    ]}}
                    """)));
        EdgarService.ConceptSeries series = svc().companyConcept("AAPL", null, "us-gaap", "Assets");
        assertThat(series.unit()).isEqualTo("USD");
        assertThat(series.datapoints()).hasSize(1);
        ConceptDatapoint d = series.datapoints().get(0);
        assertThat(d.value()).isEqualByComparingTo("365000000000");
        assertThat(d.periodStart()).isEqualTo(java.time.LocalDate.parse("2024-01-01"));
        assertThat(d.periodEnd()).isEqualTo(java.time.LocalDate.parse("2024-12-31"));
        assertThat(d.fiscalYear()).isEqualTo(2024);
        assertThat(d.fiscalPeriod()).isEqualTo("FY");
        assertThat(d.form()).isEqualTo("10-K");
        assertThat(d.filed()).isEqualTo(java.time.LocalDate.parse("2025-01-31"));
    }

    @Test void keepsAllRowsSortedByPeriodEndDescending() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/Assets.json"))
                .willReturn(okJson("""
                    {"units":{"USD":[
                      {"start":"2023-01-01","end":"2023-12-31","val":350000000000,"fy":2023,"fp":"FY","form":"10-K","filed":"2024-01-31"},
                      {"start":"2022-07-01","end":"2022-09-30","val":330000000000,"fy":2022,"fp":"Q3","form":"10-Q","filed":"2022-10-28"},
                      {"start":"2024-01-01","end":"2024-12-31","val":365000000000,"fy":2024,"fp":"FY","form":"10-K","filed":"2025-01-31"}
                    ]}}
                    """)));
        EdgarService.ConceptSeries series = svc().companyConcept("AAPL", null, "us-gaap", "Assets");
        assertThat(series.datapoints()).hasSize(3);
        assertThat(series.datapoints().get(0).periodEnd()).isEqualTo(java.time.LocalDate.parse("2024-12-31"));
        assertThat(series.datapoints().get(1).periodEnd()).isEqualTo(java.time.LocalDate.parse("2023-12-31"));
        assertThat(series.datapoints().get(2).periodEnd()).isEqualTo(java.time.LocalDate.parse("2022-09-30"));
    }

    @Test void defaultsTaxonomyToUsGaapWhenBlank() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/Assets.json"))
                .willReturn(okJson("""
                    {"units":{"USD":[
                      {"start":"2024-01-01","end":"2024-12-31","val":1,"fy":2024,"fp":"FY","form":"10-K","filed":"2025-01-31"}
                    ]}}
                    """)));
        EdgarService.ConceptSeries series = svc().companyConcept("AAPL", null, "  ", "Assets");
        assertThat(series.datapoints()).hasSize(1);
    }

    @Test void notFoundConceptYieldsEmptySeries() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000320193/us-gaap/Nonexistent.json"))
                .willReturn(aResponse().withStatus(404)));
        EdgarService.ConceptSeries series = svc().companyConcept("AAPL", null, "us-gaap", "Nonexistent");
        assertThat(series.unit()).isNull();
        assertThat(series.datapoints()).isEmpty();
    }

    @Test void unknownSymbolThrowsNotFound() {
        EdgarCikResolver cik = new EdgarCikResolver(RestClient.builder().baseUrl(wm.baseUrl()).build()) {
            @Override public Optional<String> cik(String t) { return Optional.empty(); }
        };
        EdgarService s = new EdgarService(RestClient.builder().baseUrl(wm.baseUrl()).build(), cik, 3600L, System::currentTimeMillis);
        assertThatThrownBy(() -> s.companyConcept("ZZZZ", null, "us-gaap", "Assets"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }
}
