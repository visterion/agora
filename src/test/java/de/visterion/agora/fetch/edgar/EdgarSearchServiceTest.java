package de.visterion.agora.fetch.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class EdgarSearchServiceTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private EdgarSearchService svc() {
        // test ctor: efts RestClient + archive base + ttl + clock
        return new EdgarSearchService(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                "https://www.sec.gov", 3600L, System::currentTimeMillis);
    }

    @Test void searchParsesHits() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("10-12B"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000050:aapl-1012b.htm","_source":{
                         "display_names":["Apple Spinco Inc. (CIK 0000320193)"],
                         "tickers":["SPNC"],"file_date":"2025-05-02","file_type":"10-12B"}}
                    ]}}
                    """)));
        List<FilingHit> hits = svc().search(List.of("10-12B"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100);
        assertThat(hits).hasSize(1);
        FilingHit h = hits.get(0);
        assertThat(h.company()).isEqualTo("Apple Spinco Inc.");   // " (CIK ...)" stripped
        assertThat(h.ticker()).isEqualTo("SPNC");
        assertThat(h.form()).isEqualTo("10-12B");
        assertThat(h.filedDate()).isEqualTo(LocalDate.parse("2025-05-02"));
        assertThat(h.url()).isEqualTo("https://www.sec.gov/Archives/edgar/data/320193/000032019325000050/aapl-1012b.htm");
    }

    @Test void emptyHitsYieldsEmptyList() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).willReturn(okJson("{\"hits\":{\"hits\":[]}}")));
        assertThat(svc().search(List.of("8-K"), null, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100)).isEmpty();
    }

    @Test void httpErrorThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> svc().search(List.of("8-K"), null, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void limitCaps() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).willReturn(okJson("""
            {"hits":{"hits":[
              {"_id":"a-1:d1.htm","_source":{"display_names":["A"],"tickers":["A"],"file_date":"2025-05-01","file_type":"8-K"}},
              {"_id":"a-2:d2.htm","_source":{"display_names":["B"],"tickers":["B"],"file_date":"2025-05-02","file_type":"8-K"}}
            ]}}""")));
        assertThat(svc().search(List.of("8-K"), null, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 1)).hasSize(1);
    }

    @Test void form4TransactionsParseXml() {
        // efts search for forms=4 returns one hit
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "display_names":["Cook Timothy (CIK 0000000001)"],"tickers":["AAPL"],
                         "file_date":"2025-05-05","file_type":"4"}}
                    ]}}
                    """)));
        // the per-hit Form-4 XML fetch (archive URL path)
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <reportingOwner><reportingOwnerId><rptOwnerName>Cook Timothy</rptOwnerName></reportingOwnerId>
                        <reportingOwnerRelationship><officerTitle>CEO</officerTitle></reportingOwnerRelationship></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-05-05</value></transactionDate>
                        <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                        <transactionAmounts>
                          <transactionShares><value>1000</value></transactionShares>
                          <transactionPricePerShare><value>190.00</value></transactionPricePerShare>
                        </transactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        // archive base points at the same WireMock server for the test
        EdgarSearchService s = new EdgarSearchService(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100);
        assertThat(tx).hasSize(1);
        Form4Transaction t = tx.get(0);
        assertThat(t.ticker()).isEqualTo("AAPL");
        assertThat(t.filerName()).isEqualTo("Cook Timothy");
        assertThat(t.filerRole()).isEqualTo("CEO");
        assertThat(t.code()).isEqualTo("P");
        assertThat(t.shares()).isEqualByComparingTo("1000");
        assertThat(t.dollarValue()).isEqualByComparingTo("190000"); // 1000 * 190
    }

    @Test void form4WithDoctypeExternalEntityIsSkipped() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "display_names":["Cook Timothy (CIK 0000000001)"],"tickers":["AAPL"],
                         "file_date":"2025-05-05","file_type":"4"}}
                    ]}}
                    """)));
        // Malicious Form-4 XML: a DOCTYPE declaring an external entity. A hardened parser
        // rejects the DOCTYPE (disallow-doctype-decl) → the per-hit try/catch skips it.
        // The entity file is served too; if it were resolved the body would contain "PWNED".
        wm.stubFor(get(urlPathEqualTo("/secret.txt"))
                .willReturn(aResponse().withStatus(200).withBody("PWNED")));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody("""
                    <?xml version="1.0"?>
                    <!DOCTYPE ownershipDocument [ <!ENTITY xxe SYSTEM "%s/secret.txt"> ]>
                    <ownershipDocument>
                      <reportingOwner><reportingOwnerId><rptOwnerName>&xxe;</rptOwnerName></reportingOwnerId>
                        <reportingOwnerRelationship><officerTitle>CEO</officerTitle></reportingOwnerRelationship></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-05-05</value></transactionDate>
                        <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                        <transactionAmounts>
                          <transactionShares><value>1000</value></transactionShares>
                          <transactionPricePerShare><value>190.00</value></transactionPricePerShare>
                        </transactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """.formatted(wm.baseUrl()))));
        EdgarSearchService s = new EdgarSearchService(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis);
        // The malicious filing is skipped: no transactions, no throw, entity never resolved.
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100);
        assertThat(tx).isEmpty();
        // The external entity file must never have been fetched (no XXE resolution).
        wm.verify(0, getRequestedFor(urlPathEqualTo("/secret.txt")));
    }
}
