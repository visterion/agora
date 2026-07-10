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
        List<FilingRef> all = svc().filings("AAPL", null, null, null, null, 40);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).url()).contains("/Archives/edgar/data/320193/000032019325000050/aapl-8k.htm");

        List<FilingRef> only8k = svc().filings("AAPL", null, "8-K", null, null, 40);
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
        assertThat(svc().filings("AAPL", null, null, null, null, 2)).hasSize(2);
    }

    @Test void fromDateFilters() {
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193.json"))
                .willReturn(okJson("""
                    {"filings":{"recent":{
                      "accessionNumber":["a-new","a-old"],
                      "form":["8-K","8-K"],
                      "filingDate":["2025-05-02","2024-12-01"],
                      "reportDate":["",""],
                      "primaryDocument":["d1.htm","d2.htm"]
                    }}}""")));
        var filtered = svc().filings("AAPL", null, null, java.time.LocalDate.parse("2025-01-01"), null, 40);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).filedDate()).isEqualTo(java.time.LocalDate.parse("2025-05-02"));
    }

    @Test void toDateFilters() {
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193.json"))
                .willReturn(okJson("""
                    {"filings":{"recent":{
                      "accessionNumber":["a-new","a-old"],
                      "form":["8-K","8-K"],
                      "filingDate":["2025-05-02","2024-12-01"],
                      "reportDate":["",""],
                      "primaryDocument":["d1.htm","d2.htm"]
                    }}}""")));
        var filtered = svc().filings("AAPL", null, null, null, java.time.LocalDate.parse("2025-01-01"), 40);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).filedDate()).isEqualTo(java.time.LocalDate.parse("2024-12-01"));
    }

    @Test void oldWindowFollowsArchiveFile() {
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193.json"))
                .willReturn(okJson("""
                    {"filings":{
                      "recent":{
                        "accessionNumber":["a-new"],
                        "form":["8-K"],
                        "filingDate":["2025-05-02"],
                        "reportDate":[""],
                        "primaryDocument":["d1.htm"]
                      },
                      "files":[
                        {"name":"CIK0000320193-submissions-001.json","filingFrom":"2010-01-01","filingTo":"2015-12-31"}
                      ]
                    }}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193-submissions-001.json"))
                .willReturn(okJson("""
                    {
                      "accessionNumber":["a-old"],
                      "form":["10-K"],
                      "filingDate":["2011-03-01"],
                      "reportDate":["2010-12-31"],
                      "primaryDocument":["aapl-10k.htm"]
                    }
                    """)));
        var filtered = svc().filings("AAPL", null, null,
                java.time.LocalDate.parse("2010-01-01"), java.time.LocalDate.parse("2011-12-31"), 40);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).accession()).isEqualTo("a-old");
        assertThat(filtered.get(0).filedDate()).isEqualTo(java.time.LocalDate.parse("2011-03-01"));
    }

    @Test void amendmentFormIncludedWithSlashAReflected() {
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193.json"))
                .willReturn(okJson("""
                    {"filings":{"recent":{
                      "accessionNumber":["a-amend"],
                      "form":["10-K/A"],
                      "filingDate":["2025-05-02"],
                      "reportDate":["2025-01-01"],
                      "primaryDocument":["aapl-10ka.htm"]
                    }}}
                    """)));
        var all = svc().filings("AAPL", null, null, null, null, 40);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).form()).isEqualTo("10-K/A");
    }

    @Test void emptyPrimaryDocumentYieldsNoUrl() {
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193.json"))
                .willReturn(okJson("""
                    {"filings":{"recent":{
                      "accessionNumber":["a-nodoc"],
                      "form":["8-K"],
                      "filingDate":["2025-05-02"],
                      "reportDate":[""],
                      "primaryDocument":[""]
                    }}}
                    """)));
        var all = svc().filings("AAPL", null, null, null, null, 40);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).url()).isNull();
    }

    @Test void combinedRecentAndArchiveOrderedByDateDescending() {
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193.json"))
                .willReturn(okJson("""
                    {"filings":{
                      "recent":{
                        "accessionNumber":["a-new1","a-new2"],
                        "form":["8-K","8-K"],
                        "filingDate":["2025-05-02","2025-04-01"],
                        "reportDate":["",""],
                        "primaryDocument":["d1.htm","d2.htm"]
                      },
                      "files":[
                        {"name":"CIK0000320193-submissions-001.json","filingFrom":"2010-01-01","filingTo":"2015-12-31"}
                      ]
                    }}
                    """)));
        // Archive page lists filings oldest-first (as EDGAR archive pages do) — appendFilings
        // preserves array order, so without an explicit sort the combined list is non-monotonic
        // (2011-03-01 followed by 2015-06-01, i.e. increasing, right after the newest recent entries).
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193-submissions-001.json"))
                .willReturn(okJson("""
                    {
                      "accessionNumber":["a-old1","a-old2"],
                      "form":["10-K","10-K"],
                      "filingDate":["2011-03-01","2015-06-01"],
                      "reportDate":["",""],
                      "primaryDocument":["d3.htm","d4.htm"]
                    }
                    """)));
        var filtered = svc().filings("AAPL", null, null,
                java.time.LocalDate.parse("2010-01-01"), java.time.LocalDate.parse("2025-12-31"), 40);
        assertThat(filtered).hasSize(4);
        for (int i = 0; i < filtered.size() - 1; i++) {
            assertThat(filtered.get(i).filedDate())
                    .as("filing at index %d not before filing at index %d", i, i + 1)
                    .isAfterOrEqualTo(filtered.get(i + 1).filedDate());
        }
        assertThat(filtered).extracting(FilingRef::accession)
                .containsExactly("a-new1", "a-new2", "a-old2", "a-old1");
    }

    @Test void nonNumericCikThrowsNotFound() {
        assertThatThrownBy(() -> svc().filings("X", "notanumber", null, null, null, 40))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test void httpErrorThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/submissions/CIK0000320193.json")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> svc().filings("AAPL", null, null, null, null, 40))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void unknownSymbolThrowsNotFound() {
        EdgarCikResolver cik = new EdgarCikResolver(RestClient.builder().baseUrl(wm.baseUrl()).build()) {
            @Override public Optional<String> cik(String t) { return Optional.empty(); }
        };
        EdgarService s = new EdgarService(RestClient.builder().baseUrl(wm.baseUrl()).build(), cik, 3600L, System::currentTimeMillis);
        assertThatThrownBy(() -> s.filings("ZZZZ", null, null, null, null, 40))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }
}
