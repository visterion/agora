package de.visterion.agora.fetch.search;

import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

class YahooSearchClientTest {

    private static final String UA = "SyntheticAgent/1.0";
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private YahooSearchClient client;

    @BeforeEach void setUp() {
        builder = RestClient.builder().baseUrl("https://yahoo.example.com")
                .defaultHeader("User-Agent", UA);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new YahooSearchClient(builder.build());
    }

    @Test void sendsBrowserUserAgentAndDerivedQuotesCount() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/finance/search")))
                .andExpect(header("User-Agent", UA))
                .andExpect(queryParam("q", "nokia"))
                .andExpect(queryParam("quotesCount", "30"))
                .andExpect(queryParam("newsCount", "0"))
                .andRespond(withSuccess("""
                    {"quotes":[{"symbol":"SYNA","shortname":"Synthetic A","exchDisp":"NYSE","quoteType":"EQUITY"}]}
                    """, MediaType.APPLICATION_JSON));

        assertThat(client.search("nokia", 30)).extracting(SearchHit::symbol).containsExactly("SYNA");
        server.verify();
    }

    @Test void rateLimitBecomesUnavailable() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/finance/search")))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> client.search("nokia", 30))
                .isInstanceOf(MarketDataException.class)
                .satisfies(e -> assertThat(((MarketDataException) e).kind())
                        .isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void serverErrorBecomesUnavailable() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/finance/search")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.search("nokia", 30))
                .isInstanceOf(MarketDataException.class);
    }
}
