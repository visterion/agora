package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.research.fundamentals.YahooCrumbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YahooCompanyDataSourceTest {

    private final ObjectMapper om = new ObjectMapper();
    private YahooCrumbClient client;
    private YahooCompanyDataSource source;

    @BeforeEach void setUp() {
        client = mock(YahooCrumbClient.class);
        source = new YahooCompanyDataSource(client);
    }

    @Test void recommendationsSortedNewestFirst() {
        String body = """
         {"quoteSummary":{"result":[{"recommendationTrend":{"trend":[
           {"period":"-2m","strongBuy":1,"buy":2,"hold":3,"sell":0,"strongSell":0},
           {"period":"0m","strongBuy":3,"buy":19,"hold":4,"sell":0,"strongSell":0},
           {"period":"-1m","strongBuy":2,"buy":18,"hold":5,"sell":1,"strongSell":0}]}}]}}""";
        when(client.quoteSummary("SAP.DE","recommendationTrend")).thenReturn(om.readTree(body));
        List<Recommendation> recs = source.recommendations("SAP.DE");
        assertThat(recs.get(0).period()).isEqualTo("0m");   // sorted latest-first
        assertThat(recs.get(0).buy()).isEqualTo(19);
        assertThat(recs).hasSize(3);
    }

    @Test void recommendationsNoCoverageReturnsEmptyList() {
        when(client.quoteSummary("ZZZZ.DE","recommendationTrend"))
                .thenReturn(om.readTree("{\"quoteSummary\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}"));
        assertThat(source.recommendations("ZZZZ.DE")).isEmpty();
    }

    @Test void profileMapsSectorToFinnhubIndustry() {
        String p = """
         {"quoteSummary":{"result":[{"assetProfile":{"sector":"Technology","industry":"Software - Application","country":"Germany"}}]}}""";
        when(client.quoteSummary("SAP.DE","assetProfile")).thenReturn(om.readTree(p));
        Profile prof = source.profile("SAP.DE");
        assertThat(prof.symbol()).isEqualTo("SAP.DE");
        assertThat(prof.profile().path("finnhubIndustry").asString("")).isEqualTo("Technology");
        assertThat(prof.profile().path("sector").asString("")).isEqualTo("Technology");
    }

    @Test void profileNoCoverageReturnsNonNullEmptyProfile() {
        when(client.quoteSummary("ZZZZ.DE","assetProfile"))
                .thenReturn(om.readTree("{\"quoteSummary\":{\"result\":null}}"));
        Profile empty = source.profile("ZZZZ.DE");
        assertThat(empty.symbol()).isEqualTo("ZZZZ.DE");
        assertThat(empty.profile()).isNotNull();
        assertThat(empty.profile().isEmpty()).isTrue();
    }
}
