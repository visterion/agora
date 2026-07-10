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
                         "ciks":["0000320193"],
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

    @Test void tickerAbsentYieldsCompanyOnly() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("10-12B"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000050:aapl-1012b.htm","_source":{
                         "display_names":["Fresh Spinco Inc. (CIK 0000320193)"],
                         "file_date":"2025-05-02","file_type":"10-12B"}}
                    ]}}
                    """)));
        List<FilingHit> hits = svc().search(List.of("10-12B"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100);
        assertThat(hits).hasSize(1);
        FilingHit h = hits.get(0);
        assertThat(h.ticker()).isEmpty();
        assertThat(h.company()).isEqualTo("Fresh Spinco Inc.");
    }

    @Test void malformedHitSkipped() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("8-K"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"a-1:d1.htm","_source":{"display_names":["Bad Corp"],"tickers":["BAD"],"file_date":"","file_type":"8-K"}},
                      {"_id":"a-2:d2.htm","_source":{"display_names":["Good Corp"],"tickers":["GOOD"],"file_date":"2025-05-02","file_type":"8-K"}}
                    ]}}
                    """)));
        List<FilingHit> hits = svc().search(List.of("8-K"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).ticker()).isEqualTo("GOOD");
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

    // H5: limit=30 with 10 hits reported per EFTS page must paginate via `from` until 30 are
    // collected — asserts three separate requests at from=0,10,20 each carrying size=100.
    @Test void limitAbovePageSizePaginatesViaFromOffset() {
        for (int page = 0; page < 3; page++) {
            wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                    .withQueryParam("forms", equalTo("8-K"))
                    .withQueryParam("from", equalTo(String.valueOf(page * 10)))
                    .withQueryParam("size", equalTo("100"))
                    .willReturn(okJson(pageOf(page * 10, 10, 30))));
        }
        List<FilingHit> hits = svc().search(List.of("8-K"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 30);
        assertThat(hits).hasSize(30);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index")).withQueryParam("from", equalTo("0")));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index")).withQueryParam("from", equalTo("10")));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index")).withQueryParam("from", equalTo("20")));
    }

    @Test void hardFetchCapStopsRunawayPagination() {
        // Every page reports a total far above the hard cap and always returns a full 100-hit
        // page, so unlimited pagination would run forever; the hard guard must stop it.
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("8-K"))
                .willReturn(okJson(pageOf(0, 100, 1_000_000))));
        List<FilingHit> hits = svc().search(List.of("8-K"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100_000);
        // capped at 1000 fetched hits (HARD_FETCH_CAP), well under the requested limit
        assertThat(hits.size()).isLessThanOrEqualTo(1000);
        wm.verify(10, getRequestedFor(urlPathEqualTo("/LATEST/search-index"))); // 1000 / 100 per page
    }

    private static String pageOf(int startId, int count, int total) {
        StringBuilder hits = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int id = startId + i;
            if (i > 0) hits.append(",");
            hits.append("""
                {"_id":"a-%d:d.htm","_source":{"display_names":["C%d"],"tickers":["C%d"],"file_date":"2025-05-01","file_type":"8-K"}}
                """.formatted(id, id, id));
        }
        return "{\"hits\":{\"total\":{\"value\":" + total + "},\"hits\":[" + hits + "]}}";
    }

    @Test void form4TransactionsParseXml() {
        // efts search for forms=4 returns one hit
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4,4/A"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Cook Timothy (CIK 0000000001)"],
                         "file_date":"2025-05-05","file_type":"4"}}
                    ]}}
                    """)));
        // the per-hit Form-4 XML fetch (archive URL path)
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>Cook Timothy</rptOwnerName></reportingOwnerId>
                        <reportingOwnerRelationship><officerTitle>CEO</officerTitle></reportingOwnerRelationship></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-05-05</value></transactionDate>
                        <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                        <transactionAmounts>
                          <transactionShares><value>1000</value></transactionShares>
                          <transactionPricePerShare><value>190.00</value></transactionPricePerShare>
                          <transactionAcquiredDisposedCode><value>A</value></transactionAcquiredDisposedCode>
                        </transactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        // archive base points at the same WireMock server for the test
        EdgarSearchService s = new EdgarSearchService(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        Form4Transaction t = tx.get(0);
        assertThat(t.ticker()).isEqualTo("AAPL");
        assertThat(t.filerName()).isEqualTo("Cook Timothy");
        assertThat(t.filerRole()).isEqualTo("CEO");
        assertThat(t.code()).isEqualTo("P");
        assertThat(t.shares()).isEqualByComparingTo("1000");
        assertThat(t.dollarValue()).isEqualByComparingTo("190000"); // 1000 * 190
        assertThat(t.acquiredDisposedCode()).isEqualTo("A");
        assertThat(t.form()).isEqualTo("4");
    }

    // M-F9: transaction date outside [from,to] must be filtered even though the filing itself
    // was filed inside the window.
    @Test void transactionOutsideWindowFilteredEvenWhenFiledInside() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4,4/A"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Cook Timothy (CIK 0000000001)"],
                         "file_date":"2025-05-10","file_type":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>Cook Timothy</rptOwnerName></reportingOwnerId></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-01-15</value></transactionDate>
                        <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                        <transactionAmounts>
                          <transactionShares><value>1000</value></transactionShares>
                          <transactionPricePerShare><value>190.00</value></transactionPricePerShare>
                        </transactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        EdgarSearchService s = new EdgarSearchService(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis);
        // Filing was filed 2025-05-10 (inside the requested window) but the actual transaction
        // happened 2025-01-15 (well outside it) — must be filtered out.
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100).transactions();
        assertThat(tx).isEmpty();
    }

    // M-F9: a filing dated just past `to` but within the widened search window, whose
    // TRANSACTION date is inside [from,to], must still be returned (late-filed-but-in-window).
    @Test void lateFiledTransactionInsideWindowIsIncluded() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4,4/A"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Cook Timothy (CIK 0000000001)"],
                         "file_date":"2025-06-05","file_type":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>Cook Timothy</rptOwnerName></reportingOwnerId></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-05-28</value></transactionDate>
                        <transactionCoding><transactionCode>S</transactionCode></transactionCoding>
                        <transactionAmounts>
                          <transactionShares><value>1000</value></transactionShares>
                          <transactionPricePerShare><value>190.00</value></transactionPricePerShare>
                        </transactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        EdgarSearchService s = new EdgarSearchService(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis);
        // Filed 2025-06-05, 5 days after the [from,to]=[..,2025-05-31] window closes — the search
        // window is widened by 10 days, so the filing is still found; its transaction (2025-05-28)
        // is inside [from,to].
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).code()).isEqualTo("S");
    }

    // Lows: 4/A amendments must be included (search forms include "4,4/A"), with the `form` field
    // exposing the amendment so callers can tell a 4 from a 4/A.
    @Test void amendmentFormIsIncludedWithFormField() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4,4/A"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4a.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Cook Timothy (CIK 0000000001)"],
                         "file_date":"2025-05-10","file_type":"4/A"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4a.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>Cook Timothy</rptOwnerName></reportingOwnerId></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-05-10</value></transactionDate>
                        <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                        <transactionAmounts>
                          <transactionShares><value>1000</value></transactionShares>
                          <transactionPricePerShare><value>190.00</value></transactionPricePerShare>
                        </transactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        EdgarSearchService s = new EdgarSearchService(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).form()).isEqualTo("4/A");
    }

    // M-F10: sequential per-hit archive GETs are throttled (~110ms spacing) via the injected
    // Sleeper — asserts sleep() is invoked once per gap between hits (n-1 times for n hits).
    @Test void form4ArchiveFetchesAreThrottled() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4,4/A"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000001:f1.xml","_source":{"ciks":["1"],"display_names":["A"],"file_date":"2025-05-01","file_type":"4"}},
                      {"_id":"0000320193-25-000002:f2.xml","_source":{"ciks":["1"],"display_names":["B"],"file_date":"2025-05-02","file_type":"4"}},
                      {"_id":"0000320193-25-000003:f3.xml","_source":{"ciks":["1"],"display_names":["C"],"file_date":"2025-05-03","file_type":"4"}}
                    ]}}
                    """)));
        String txXml = """
                <ownershipDocument>
                  <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                  <reportingOwner><reportingOwnerId><rptOwnerName>X</rptOwnerName></reportingOwnerId></reportingOwner>
                  <nonDerivativeTable><nonDerivativeTransaction>
                    <transactionDate><value>2025-05-01</value></transactionDate>
                    <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                    <transactionAmounts>
                      <transactionShares><value>1</value></transactionShares>
                      <transactionPricePerShare><value>1</value></transactionPricePerShare>
                    </transactionAmounts>
                  </nonDerivativeTransaction></nonDerivativeTable>
                </ownershipDocument>
                """;
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/1/000032019325000001/f1.xml")).willReturn(aResponse().withHeader("Content-Type","application/xml").withBody(txXml)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/1/000032019325000002/f2.xml")).willReturn(aResponse().withHeader("Content-Type","application/xml").withBody(txXml)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/1/000032019325000003/f3.xml")).willReturn(aResponse().withHeader("Content-Type","application/xml").withBody(txXml)));

        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        EdgarSearchService.Sleeper recordingSleeper = sleeps::add;
        EdgarSearchService s = new EdgarSearchService(
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, recordingSleeper, 5L * 1024 * 1024);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).transactions();
        assertThat(tx).hasSize(3);
        assertThat(sleeps).hasSize(2); // n-1 gaps between 3 sequential archive GETs
        assertThat(sleeps).allMatch(ms -> ms == 110L);
    }

    // M-F10: an aggregate deadline caps the sequential archive GETs; on deadline the result is
    // partial AND marked truncated.
    @Test void form4DeadlineTruncatesAndMarksResult() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4,4/A"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000001:f1.xml","_source":{"ciks":["1"],"display_names":["A"],"file_date":"2025-05-01","file_type":"4"}},
                      {"_id":"0000320193-25-000002:f2.xml","_source":{"ciks":["1"],"display_names":["B"],"file_date":"2025-05-02","file_type":"4"}}
                    ]}}
                    """)));
        String txXml = """
                <ownershipDocument>
                  <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                  <reportingOwner><reportingOwnerId><rptOwnerName>X</rptOwnerName></reportingOwnerId></reportingOwner>
                  <nonDerivativeTable><nonDerivativeTransaction>
                    <transactionDate><value>2025-05-01</value></transactionDate>
                    <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                    <transactionAmounts>
                      <transactionShares><value>1</value></transactionShares>
                      <transactionPricePerShare><value>1</value></transactionPricePerShare>
                    </transactionAmounts>
                  </nonDerivativeTransaction></nonDerivativeTable>
                </ownershipDocument>
                """;
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/1/000032019325000001/f1.xml")).willReturn(aResponse().withHeader("Content-Type","application/xml").withBody(txXml)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/1/000032019325000002/f2.xml")).willReturn(aResponse().withHeader("Content-Type","application/xml").withBody(txXml)));

        // Clock jumps 40s (past the 30s deadline) on every call after the first, so the
        // deadline check before the 2nd hit trips immediately — no real sleeping needed.
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0L);
        java.util.function.LongSupplier now = () -> clock.getAndAdd(40_000L);
        EdgarSearchService s = new EdgarSearchService(
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, now, (EdgarSearchService.Sleeper) ms -> {}, 5L * 1024 * 1024);
        EdgarSearchService.Form4Result result = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100);
        assertThat(result.truncated()).isTrue();
        assertThat(result.transactions()).hasSizeLessThan(2);
    }

    // Lows: naive ":"-joined cache keys collide when a field itself contains ":" — different
    // search calls must not share a cache entry.
    @Test void cacheKeyDoesNotCollideOnColonBearingFields() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("a:b"))
                .willReturn(okJson("{\"hits\":{\"hits\":[]}}")));
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("a"))
                .withQueryParam("q", equalTo("b:c"))
                .willReturn(okJson("{\"hits\":{\"hits\":[]}}")));
        EdgarSearchService s = svc();
        // Old ":"-join: "search:" + "a:b" + ":" + null + ... vs "search:" + "a" + ":" + "b:c" + ...
        // collide into the same string. Must be two independent cache entries → two requests.
        s.search(List.of("a:b"), null, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 5);
        s.search(List.of("a"), "b:c", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 5);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index")).withQueryParam("forms", equalTo("a:b")));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index")).withQueryParam("forms", equalTo("a")).withQueryParam("q", equalTo("b:c")));
    }

    @Test void form4RealEftsStructureParsesTickerFromXml() {
        // Real efts Form-4 _source: has `ciks` (array), `display_names`, file_type/file_date —
        // but NO `tickers` field. The ticker must come from the fetched XML (issuerTradingSymbol).
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4,4/A"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Apple Inc.  (CIK 0000320193)"],
                         "file_date":"2025-05-05","file_type":"4"}}
                    ]}}
                    """)));
        // Archive path derived from ciks[0] (320193), not from an absent tickers field.
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerCik>0000320193</issuerCik><issuerName>Apple Inc.</issuerName>
                        <issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
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
        EdgarSearchService s = new EdgarSearchService(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).ticker()).isEqualTo("AAPL");
        assertThat(tx.get(0).filerName()).isEqualTo("Cook Timothy");
        assertThat(tx.get(0).code()).isEqualTo("P");
    }

    @Test void form4UsesCiksNotAccessionPrefixForArchiveUrl() {
        // Accession prefix is the filing-agent CIK (1140361); the correct archive-path CIK is
        // ciks[0] (2140696). The old code built the URL from the accession prefix → 404 → empty.
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4,4/A"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0001140361-26-025622:form4.xml","_source":{
                         "ciks":["0002140696"],
                         "display_names":["Some Filer (CIK 0002140696)"],
                         "file_date":"2026-01-05","file_type":"4"}}
                    ]}}
                    """)));
        // Only the ciks[0]-derived path is stubbed; the accession-prefix path (1140361) is not.
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/2140696/000114036126025622/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerTradingSymbol>NPB</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>Jane Filer</rptOwnerName></reportingOwnerId></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2026-01-05</value></transactionDate>
                        <transactionCoding><transactionCode>S</transactionCode></transactionCoding>
                        <transactionAmounts>
                          <transactionShares><value>500</value></transactionShares>
                          <transactionPricePerShare><value>10.00</value></transactionPricePerShare>
                        </transactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        EdgarSearchService s = new EdgarSearchService(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).ticker()).isEqualTo("NPB");
    }

    @Test void form4WithDoctypeExternalEntityNeverResolvesEntity() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4,4/A"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Cook Timothy (CIK 0000000001)"],
                         "file_date":"2025-05-05","file_type":"4"}}
                    ]}}
                    """)));
        // Malicious Form-4 XML: a DOCTYPE declaring an external entity. M-C5: DOCTYPE alone is now
        // allowed (disallow-doctype-decl=false, real Form 4s carry one) — but external-general-
        // entities stays off, so &xxe; never resolves to the fetched-file content ("PWNED").
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
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).transactions();
        // Document still parses (DOCTYPE no longer fatal) but the entity content never resolved —
        // filerName is empty, definitely not "PWNED".
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).filerName()).doesNotContain("PWNED");
        // The external entity file must never have been fetched (no XXE resolution).
        wm.verify(0, getRequestedFor(urlPathEqualTo("/secret.txt")));
    }

    // M-C5 low: a benign DOCTYPE (no external entity) must parse successfully — the old
    // disallow-doctype-decl=true setting rejected ALL DOCTYPE'd Form 4s, even harmless ones.
    @Test void form4WithBenignDoctypeParses() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4,4/A"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Cook Timothy (CIK 0000000001)"],
                         "file_date":"2025-05-05","file_type":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody("""
                    <?xml version="1.0"?>
                    <!DOCTYPE ownershipDocument>
                    <ownershipDocument>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
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
        EdgarSearchService s = new EdgarSearchService(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).ticker()).isEqualTo("AAPL");
        assertThat(tx.get(0).filerName()).isEqualTo("Cook Timothy");
    }

    @Test void filingTextFetchesArchiveDocAndExtracts() {
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/1/x.htm"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody(
                    "<html><body><p>cover</p><p>SUMMARY TERM SHEET</p>"
                  + "<p>The offer is $52.00 in cash per share.</p></body></html>")));
        var svc = new EdgarSearchService(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), wm.baseUrl(), 3600L, System::currentTimeMillis);

        var ft = svc.filingText(wm.baseUrl() + "/Archives/edgar/data/1/x.htm");

        assertThat(ft.sectionFound()).isTrue();
        assertThat(ft.text()).contains("SUMMARY TERM SHEET").contains("$52.00");
        assertThat(ft.charCount()).isEqualTo(ft.text().length());
        assertThat(ft.sourceUrl()).endsWith("/Archives/edgar/data/1/x.htm");
    }

    @Test void filingTextRejectsNonArchiveUrl() {
        var svc = new EdgarSearchService(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), wm.baseUrl(), 3600L, System::currentTimeMillis);
        assertThatThrownBy(() -> svc.filingText("https://evil.example/secret"))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void filingTextEmptyDocumentIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/2/empty.htm"))
                .willReturn(aResponse().withBody("")));
        var svc = new EdgarSearchService(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), wm.baseUrl(), 3600L, System::currentTimeMillis);
        assertThatThrownBy(() -> svc.filingText(wm.baseUrl() + "/Archives/edgar/data/2/empty.htm"))
                .isInstanceOf(MarketDataException.class);
    }

    // M-C3: a filing body over the configured size cap must be rejected rather than fully
    // buffered. Uses a tiny injected cap so the test doesn't need a multi-MB body.
    @Test void filingTextOverSizeCapIsUnavailable() {
        String body = "x".repeat(200);
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/3/big.htm"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody(body)));
        var svc = new EdgarSearchService(
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis,
                (EdgarSearchService.Sleeper) ms -> {}, 100L); // 100-byte cap, body is 200 bytes
        assertThatThrownBy(() -> svc.filingText(wm.baseUrl() + "/Archives/edgar/data/3/big.htm"))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void filingTextUnderSizeCapStillWorks() {
        String body = "<p>SUMMARY TERM SHEET</p><p>ok</p>";
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/4/small.htm"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody(body)));
        var svc = new EdgarSearchService(
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, System::currentTimeMillis,
                (EdgarSearchService.Sleeper) ms -> {}, 1024L);
        var ft = svc.filingText(wm.baseUrl() + "/Archives/edgar/data/4/small.htm");
        assertThat(ft.sectionFound()).isTrue();
    }
}
