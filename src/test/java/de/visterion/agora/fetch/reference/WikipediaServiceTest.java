package de.visterion.agora.fetch.reference;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class WikipediaServiceTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private WikipediaService svc() {
        return new WikipediaService(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                "List of S&P 500 companies", 86400L, System::currentTimeMillis);
    }

    @Test void parsesConstituents() {
        // Minimal wikitext table with Symbol, Security, GICS Sector, Date added columns
        String wikitext = "{|\n! Symbol !! Security !! GICS Sector !! Date added\n" +
                "|-\n| [[Apple Inc.|AAPL]] || Apple Inc. || Information Technology || 1982-11-30\n" +
                "|-\n| MSFT || Microsoft || Information Technology || 1994-06-01\n|}";
        wm.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(okJson("{\"parse\":{\"wikitext\":" + toJsonString(wikitext) + "}}")));
        List<Constituent> c = svc().constituents("sp500");
        assertThat(c).hasSize(2);
        assertThat(c.get(0).symbol()).isEqualTo("AAPL");
        assertThat(c.get(0).sector()).isEqualTo("Information Technology");
    }

    @Test void unknownIndexThrowsUnavailable() {
        assertThatThrownBy(() -> svc().constituents("nasdaq100")).isInstanceOf(MarketDataException.class);
    }

    @Test void fetchFailureThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/w/api.php")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> svc().constituents("sp500")).isInstanceOf(MarketDataException.class);
    }

    // Helper: JSON-encode a string (quotes + escapes) for embedding in the stub body.
    private static String toJsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
