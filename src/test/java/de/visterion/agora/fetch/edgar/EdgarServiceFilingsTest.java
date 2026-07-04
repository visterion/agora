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

class EdgarServiceFilingsTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private EdgarService svc() {
        EdgarCikResolver cik = new EdgarCikResolver(RestClient.builder().baseUrl(wm.baseUrl()).build()) {
            @Override public Optional<String> cik(String t) { return Optional.of("0000320193"); }
        };
        return new EdgarService(RestClient.builder().baseUrl(wm.baseUrl()).build(), cik,
                3600L, System::currentTimeMillis);
    }

    @Test void filingsParseAndFilterByForm() {
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193.json"))
                .willReturn(okJson("""
                    {"cik":"320193","filings":{"recent":{
                      "accessionNumber":["0000320193-25-000050","0000320193-25-000049"],
                      "form":["8-K","10-Q"],
                      "filingDate":["2025-05-02","2025-05-01"],
                      "reportDate":["2025-05-01","2025-03-31"],
                      "primaryDocument":["aapl-8k.htm","aapl-10q.htm"]
                    }}}
                    """)));
        List<FilingRef> all = svc().filings("AAPL", null, null, null, 40);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).url()).contains("/Archives/edgar/data/320193/000032019325000050/aapl-8k.htm");

        List<FilingRef> only8k = svc().filings("AAPL", null, "8-K", null, 40);
        assertThat(only8k).hasSize(1);
        assertThat(only8k.get(0).form()).isEqualTo("8-K");
    }

    @Test void limitCaps() {
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193.json"))
                .willReturn(okJson("""
                    {"filings":{"recent":{
                      "accessionNumber":["a-1","a-2","a-3"],
                      "form":["8-K","8-K","8-K"],
                      "filingDate":["2025-05-03","2025-05-02","2025-05-01"],
                      "reportDate":["","",""],
                      "primaryDocument":["d1.htm","d2.htm","d3.htm"]
                    }}}
                    """)));
        assertThat(svc().filings("AAPL", null, null, null, 2)).hasSize(2);
    }

    @Test void httpErrorThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193.json")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> svc().filings("AAPL", null, null, null, 40))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void unknownSymbolThrowsNotFound() {
        EdgarCikResolver cik = new EdgarCikResolver(RestClient.builder().baseUrl(wm.baseUrl()).build()) {
            @Override public Optional<String> cik(String t) { return Optional.empty(); }
        };
        EdgarService s = new EdgarService(RestClient.builder().baseUrl(wm.baseUrl()).build(), cik, 3600L, System::currentTimeMillis);
        assertThatThrownBy(() -> s.filings("ZZZZ", null, null, null, 40))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }
}
