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

    /**
     * The SEC ticker universe the service validates extracted symbols against — synthetic CIKs
     * and symbols, never an export of the real file. Deliberately one entry per fixture CIK so a
     * test cannot pass by borrowing another fixture's symbol.
     */
    static final java.util.Map<String, List<String>> UNIVERSE = java.util.Map.of(
            "0000320193", List.of("SPNC"),
            "0001739445", List.of("ACA"),
            "0000000042", List.of("ACME"),
            "0000000043", List.of("BRK-B"),   // SEC spells share classes with '-', callers with '.'
            "0000000002", List.of("GOOD"),
            "0000000001", List.of("BAD"));

    static final TickerUniverse TICKERS = cik -> {
        String key = EdgarCikResolver.normalizeCik(cik);
        return key == null ? List.of() : UNIVERSE.getOrDefault(key, List.of());
    };

    /** A universe that knows nothing — models both an unlisted filer and an unreachable SEC file. */
    static final TickerUniverse NO_TICKERS = cik -> List.of();

    /**
     * A RestClient pointed at WireMock and built through
     * {@link de.visterion.agora.data.DataHttp#clientBuilder(long)} — the SAME factory the
     * production constructor pins.
     *
     * <p><b>Never a bare {@code RestClient.builder()} here (BUG-S20).</b> A bare builder sets no
     * request factory, so Spring auto-detects one from the classpath and finds Apache HttpClient 5,
     * whose {@code DefaultHttpRequestRetryStrategy} silently repeats an idempotent GET on 429/503.
     * Measured while building the EFTS retry: a single 429 stub was hit TWICE with no retry code in
     * the service at all. Production is not affected — {@code DataHttp} pins
     * {@code JdkClientHttpRequestFactory} — but a test on a bare builder measures a retry layer
     * production does not have, i.e. it can pass on behaviour that does not exist while missing a
     * regression in the behaviour that does.
     *
     * <p>The 15 s read timeout is the default of {@code agora.fetch.timeout-ms}, i.e. what the
     * production constructor passes.
     */
    private static RestClient wmClient() {
        return de.visterion.agora.data.DataHttp.clientBuilder(15_000).baseUrl(wm.baseUrl()).build();
    }

    private EdgarSearchService svc() {
        // test ctor: efts RestClient + archive base + ttl + clock + ticker universe
        return new EdgarSearchService(wmClient(),
                "https://www.sec.gov", 3600L, System::currentTimeMillis, TICKERS);
    }

    // REAL efts wire shape: there is NO `tickers` key — the ticker lives only inside
    // display_names[0] as the parenthesised group in front of the "(CIK ...)" group.
    @Test void searchParsesHits() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("10-12B"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000050:aapl-1012b.htm","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Apple Spinco Inc.  (SPNC)  (CIK 0000320193)"],
                         "file_date":"2025-05-02","file_type":"10-12B"}}
                    ]}}
                    """)));
        List<FilingHit> hits = svc().search(List.of("10-12B"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100);
        assertThat(hits).hasSize(1);
        FilingHit h = hits.get(0);
        // company keeps the ticker segment (unchanged behaviour: only " (CIK ...)" is stripped)
        assertThat(h.company()).isEqualTo("Apple Spinco Inc.  (SPNC)");
        assertThat(h.ticker()).isEqualTo("SPNC");
        assertThat(h.form()).isEqualTo("10-12B");
        assertThat(h.filedDate()).isEqualTo(LocalDate.parse("2025-05-02"));
        assertThat(h.url()).isEqualTo("https://www.sec.gov/Archives/edgar/data/320193/000032019325000050/aapl-1012b.htm");
    }

    // Many filers have no listed ticker at all — display_names carries only the CIK group.
    @Test void tickerAbsentYieldsCompanyOnly() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("10-12B"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000050:aapl-1012b.htm","_source":{
                         "display_names":["Fresh Spinco Inc.  (CIK 0000320193)"],
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

    // A parenthesised phrase in the company name itself must never be mistaken for a ticker:
    // it is too long / not ticker-shaped, and the name carries no ticker group.
    @Test void companyNameWithParenthesesYieldsNoTicker() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("10-12B"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000050:x.htm","_source":{
                         "display_names":["Acme Capital (Holdings) Limited  (CIK 0000320193)"],
                         "file_date":"2025-05-02","file_type":"10-12B"}}
                    ]}}
                    """)));
        FilingHit h = svc().search(List.of("10-12B"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).get(0);
        assertThat(h.ticker()).isEmpty();
        assertThat(h.company()).isEqualTo("Acme Capital (Holdings) Limited");
    }

    // Same, but WITH a real ticker group behind the parenthesised name part — anchoring on the
    // CIK group must pick the ticker, not the name's parentheses.
    @Test void companyNameWithParenthesesStillYieldsTicker() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("10-12B"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000000042-25-000050:x.htm","_source":{
                         "ciks":["0000000042"],
                         "display_names":["Acme Capital (Holdings) Limited  (ACME)  (CIK 0000000042)"],
                         "file_date":"2025-05-02","file_type":"10-12B"}}
                    ]}}
                    """)));
        FilingHit h = svc().search(List.of("10-12B"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).get(0);
        assertThat(h.ticker()).isEqualTo("ACME");
        assertThat(h.company()).isEqualTo("Acme Capital (Holdings) Limited  (ACME)");
    }

    // Spacing between the groups is not guaranteed to be exactly two blanks, and a class-share
    // ticker carries a dot; a lowercase source value is normalised to upper case.
    @Test void tickerToleratesSpacingAndIsUppercased() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("10-12B"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000000043-25-000050:x.htm","_source":{
                         "ciks":["0000000043"],
                         "display_names":["Berkshire Test Inc.     (brk.b)   (CIK 0000000043)"],
                         "file_date":"2025-05-02","file_type":"10-12B"}}
                    ]}}
                    """)));
        FilingHit h = svc().search(List.of("10-12B"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).get(0);
        assertThat(h.ticker()).isEqualTo("BRK.B");
    }

    // Real production shape that motivated the fix (run AF1A35BA365B429FAE3015E199448219).
    @Test void realArcosaDisplayNameYieldsTicker() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("DEFM14A"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0001739445-26-000123:defm14a.htm","_source":{
                         "ciks":["0001739445"],"file_num":["001-38494"],
                         "display_names":["Arcosa, Inc.  (ACA)  (CIK 0001739445)"],
                         "root_forms":["DEFM14A"],"file_date":"2026-08-03","file_type":"DEFM14A"}}
                    ]}}
                    """)));
        FilingHit h = svc().search(List.of("DEFM14A"), null,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), 100).get(0);
        assertThat(h.ticker()).isEqualTo("ACA");
        assertThat(h.company()).isEqualTo("Arcosa, Inc.  (ACA)");
    }

    // ---- A1: the trailing group is a CANDIDATE, never a ticker on its own authority ----------
    // EDGAR conformed names themselves end in parentheticals. Measured over SEC's full
    // cik-lookup-data.txt (1,053,510 names, 2026-08-04): 635 names end in a group that the
    // shape pattern accepts, and 183 of those collide with a real listed ticker. The group is
    // only a ticker when the FILER'S OWN CIK is listed under it in company_tickers.json.

    /**
     * Every display name here is a verbatim live EFTS hit (efts.sec.gov, 2026-08-04). None of
     * these filers has the printed group as a listed symbol, so each must yield "" — the old
     * end-anchored heuristic returned the group and thereby routed a quote lookup and a merger
     * spread at International Paper, Tejon Ranch, Deere, Halliburton and Visa.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "Grayscale Story Trust (IP)  (CIK 0002079251)|0002079251",
            "Bullion Monarch Mining, Inc. (NEW)  (CIK 0001497246)|0001497246",
            "Tower Research Capital LLC (TRC)  (CIK 0001533421)|0001533421",
            "Grayscale XRP Trust (XRP)  (CIK 0001732410)|0001732410",
            "ACUITY INC. (DE)  (CIK 0001144215)|0001144215",
            "ADAMS CHARLES (HAL)  (CIK 0000000011)|0000000011",
            "NTL (V)  (CIK 0000000012)|0000000012",
            "EUROFOIL INC. (USA)  (CIK 0000000013)|0000000013",
            "MUZINICH & CO. LTD (UK)  (CIK 0000000014)|0000000014",
            "SYNTHETIC HOLDING AB (PUBL)  (CIK 0000000015)|0000000015",
            "SYNTHETIC PARTNERS LP (PS)  (CIK 0000000016)|0000000016",
            "SYNTHETIC CORP (OLD)  (CIK 0000000017)|0000000017",
    })
    void trailingGroupOfAnUnlistedFilerIsNeverEmittedAsATicker(String displayName, String cik) {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"a-1:d.htm","_source":{
                         "ciks":["%s"],"display_names":["%s"],
                         "file_date":"2026-05-02","file_type":"S-4"}}
                    ]}}
                    """.formatted(cik, displayName))));
        FilingHit h = svc().search(List.of("S-4"), null,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), 100).get(0);
        assertThat(h.ticker()).isEmpty();
        // the hit itself survives — only the invented symbol is gone
        assertThat(h.company()).isNotEmpty();
    }

    /** A group that IS a real symbol, but of a DIFFERENT company, is the worst case: it defeats
     *  the empty-hit guard and sends downstream lookups to the wrong issuer. */
    @Test void groupThatIsARealSymbolOfAnotherCompanyIsRejected() {
        // 0000000042 is listed as ACME; the printed group "GOOD" belongs to CIK 0000000002.
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"a-1:d.htm","_source":{
                         "ciks":["0000000042"],
                         "display_names":["Acme Spinco Trust  (GOOD)  (CIK 0000000042)"],
                         "file_date":"2026-05-02","file_type":"S-4"}}
                    ]}}
                    """)));
        FilingHit h = svc().search(List.of("S-4"), null,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), 100).get(0);
        assertThat(h.ticker()).isEmpty();
    }

    /** Lookup failure (SEC file unreachable / cold-start outage) must NOT re-enable guessing. */
    @Test void unavailableTickerUniverseYieldsNoTickerRatherThanTheRawGroup() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"a-1:d.htm","_source":{
                         "ciks":["0001739445"],
                         "display_names":["Arcosa, Inc.  (ACA)  (CIK 0001739445)"],
                         "file_date":"2026-05-02","file_type":"S-4"}}
                    ]}}
                    """)));
        var s = new EdgarSearchService(wmClient(),
                "https://www.sec.gov", 3600L, System::currentTimeMillis, NO_TICKERS);
        FilingHit h = s.search(List.of("S-4"), null,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), 100).get(0);
        assertThat(h.ticker()).isEmpty();
        assertThat(h.company()).isEqualTo("Arcosa, Inc.  (ACA)");
    }

    /** A hit with no `ciks` at all cannot be validated, so it cannot carry a symbol. */
    @Test void hitWithoutCiksYieldsNoTicker() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"a-1:d.htm","_source":{
                         "display_names":["Arcosa, Inc.  (ACA)  (CIK 0001739445)"],
                         "file_date":"2026-05-02","file_type":"S-4"}}
                    ]}}
                    """)));
        FilingHit h = svc().search(List.of("S-4"), null,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), 100).get(0);
        assertThat(h.ticker()).isEmpty();
    }

    // ---- A2: EFTS prints ALL of a filer's symbols in ONE comma-separated group ---------------
    // 502 of 3,454 live hits (14.5%) — DEFM14A 25% — carried such a group and yielded "" because
    // the shape pattern requires a full single-symbol match. Every ADR/dual-listed merger target
    // and every SPAC unit/warrant filer is in that set.

    /**
     * Verbatim live EFTS display names. The expected symbol is the primary one, i.e. the first
     * printed element that the filer's CIK is actually listed under.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "WPP plc  (WPP, WPPGF)|WPP,WPPGF|WPP",
            "Equitable Holdings, Inc.  (EQH, EQH-PA, EQH-PC)|EQH,EQH-PA,EQH-PC|EQH",
            "Inflection Point Acquisition Corp. III (IPCX, IPCXR, IPCXU)|IPCX,IPCXR,IPCXU|IPCX",
            "National Storage Affiliates Trust (NSA, NSA-PA, NSA-PB)|NSA,NSA-PA,NSA-PB|NSA",
            "Unilever PLC  (UL, UNLYF)|UL,UNLYF|UL",
            "Lloyds Banking Group plc  (LYG, LLDTF, LLOBF)|LYG,LLDTF,LLOBF|LYG",
            "HSBC Holdings plc  (HSBC, HBCYF)|HSBC,HBCYF|HSBC",
            "Shell plc  (SHEL, RYDAF)|SHEL,RYDAF|SHEL",
            "Vodafone Group Public Ltd Co  (VOD, VODPF)|VOD,VODPF|VOD",
            "IonQ, Inc.  (IONQ, IONQ-WT)|IONQ,IONQ-WT|IONQ",
            "SoundHound AI, Inc.  (SOUN, SOUNW)|SOUN,SOUNW|SOUN",
    })
    void multiTickerGroupYieldsThePrimarySymbol(String name, String listedCsv, String expected) {
        String cik = "0000000099";
        var universe = (TickerUniverse) c -> EdgarCikResolver.normalizeCik(c) == null
                || !EdgarCikResolver.normalizeCik(c).equals(cik)
                ? List.<String>of() : List.of(listedCsv.split(","));
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"a-1:d.htm","_source":{
                         "ciks":["%s"],"display_names":["%s  (CIK %s)"],
                         "file_date":"2026-05-02","file_type":"S-4"}}
                    ]}}
                    """.formatted(cik, name, cik))));
        var s = new EdgarSearchService(wmClient(),
                "https://www.sec.gov", 3600L, System::currentTimeMillis, universe);
        FilingHit h = s.search(List.of("S-4"), null,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), 100).get(0);
        assertThat(h.ticker()).isEqualTo(expected);
    }

    /** In a multi-symbol group, an unlisted first element must not shadow a listed later one. */
    @Test void multiTickerGroupSkipsElementsTheCikIsNotListedUnder() {
        var universe = (TickerUniverse) c -> List.of("GOOD");
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"a-1:d.htm","_source":{
                         "ciks":["0000000002"],
                         "display_names":["Synthetic Corp  (NEW, GOOD)  (CIK 0000000002)"],
                         "file_date":"2026-05-02","file_type":"S-4"}}
                    ]}}
                    """)));
        var s = new EdgarSearchService(wmClient(),
                "https://www.sec.gov", 3600L, System::currentTimeMillis, universe);
        assertThat(s.search(List.of("S-4"), null,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), 100).get(0).ticker())
                .isEqualTo("GOOD");
    }

    @Test void malformedHitSkipped() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("8-K"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"a-1:d1.htm","_source":{"ciks":["0000000001"],"display_names":["Bad Corp  (BAD)  (CIK 0000000001)"],"file_date":"","file_type":"8-K"}},
                      {"_id":"a-2:d2.htm","_source":{"ciks":["0000000002"],"display_names":["Good Corp  (GOOD)  (CIK 0000000002)"],"file_date":"2025-05-02","file_type":"8-K"}}
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
              {"_id":"a-1:d1.htm","_source":{"display_names":["Alpha Corp  (A)  (CIK 0000000001)"],"file_date":"2025-05-01","file_type":"8-K"}},
              {"_id":"a-2:d2.htm","_source":{"display_names":["Beta Corp  (B)  (CIK 0000000002)"],"file_date":"2025-05-02","file_type":"8-K"}}
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
                {"_id":"a-%d:d.htm","_source":{"display_names":["Corp %d  (CIK 0000000001)"],"file_date":"2025-05-01","file_type":"8-K"}}
                """.formatted(id, id));
        }
        return "{\"hits\":{\"total\":{\"value\":" + total + "},\"hits\":[" + hits + "]}}";
    }

    @Test void form4TransactionsParseXml() {
        // efts search for forms=4 returns one hit
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
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
                      <aff10b5One>1</aff10b5One>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerCik>0001214156</rptOwnerCik>
                        <rptOwnerName>Cook Timothy</rptOwnerName></reportingOwnerId>
                        <reportingOwnerRelationship><officerTitle>CEO</officerTitle></reportingOwnerRelationship></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-05-05</value></transactionDate>
                        <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                        <transactionAmounts>
                          <transactionShares><value>1000</value></transactionShares>
                          <transactionPricePerShare><value>190.00</value></transactionPricePerShare>
                          <transactionAcquiredDisposedCode><value>A</value></transactionAcquiredDisposedCode>
                        </transactionAmounts>
                        <postTransactionAmounts>
                          <sharesOwnedFollowingTransaction><value>34567</value></sharesOwnedFollowingTransaction>
                        </postTransactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        // archive base points at the same WireMock server for the test
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
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
        assertThat(t.price()).isEqualByComparingTo("190.00");
        assertThat(t.sharesOwnedFollowing()).isEqualByComparingTo("34567");
        assertThat(t.aff10b5One()).isTrue();
        assertThat(t.filerCik()).isEqualTo("0001214156");
    }

    /**
     * Stubs one Form-4 hit whose XML carries {@code issuerBlock} verbatim inside {@code <issuer>}
     * (pass {@code ""} to omit the symbol element entirely) and returns the parsed transactions.
     * All values synthetic — an invented issuer, never a captured live filing.
     */
    private List<Form4Transaction> form4WithIssuerBlock(String issuerBlock) {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Synthetic Filer One (CIK 0000000001)"],
                         "file_date":"2025-05-05","file_type":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerName>SYNTHETIC HOLDINGS INC</issuerName>%s</issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerCik>0000000777</rptOwnerCik>
                        <rptOwnerName>Synthetic Filer One</rptOwnerName></reportingOwnerId></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-05-05</value></transactionDate>
                        <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                        <transactionAmounts>
                          <transactionShares><value>1000</value></transactionShares>
                          <transactionPricePerShare><value>10.00</value></transactionPricePerShare>
                          <transactionAcquiredDisposedCode><value>A</value></transactionAcquiredDisposedCode>
                        </transactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """.formatted(issuerBlock))));
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        return s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).transactions();
    }

    /**
     * {@code issuerTradingSymbol} is FREE TEXT written by the filer. When the issuer has no listed
     * symbol, filers type a placeholder, and passing it through poisons any consumer that groups
     * Form-4 rows BY TICKER STRING: every unlisted issuer in the window collapses into one bucket
     * and filers of unrelated companies combine into an insider cluster that does not exist.
     *
     * <p>Two gates, both exercised here: the {@code TICKER} shape test (which "N/A", "--" and
     * "NOT APPLICABLE" fail) and the explicit placeholder list (which "NONE" and "NA" need,
     * since both are shape-valid). Rejected → empty symbol, and the transaction is still emitted:
     * filer and issuer are known regardless.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "N/A", "n/a", "N.A.", "--", "-", "NOT APPLICABLE", "NO SYMBOL", "not listed",
            "NONE", "None", "none", "NON", "NA", "na", "NIL", "NULL", "TBD", "TBA",
            "UNK", "PVT", "PRIV", "XXXXX", "0", "000",
            "GOOG/GOOGL", "synth*88",
    })
    void form4PlaceholderIssuerSymbolYieldsEmptyTickerAndStillEmitsTheTransaction(String placeholder) {
        List<Form4Transaction> tx =
                form4WithIssuerBlock("<issuerTradingSymbol>" + placeholder + "</issuerTradingSymbol>");
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).ticker()).isEmpty();
        assertThat(tx.get(0).filerName()).isEqualTo("Synthetic Filer One");
    }

    /** A genuine symbol, a share class and a lower-case spelling all survive (upper-cased). */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "SYNA|SYNA",
            "SYNA.B|SYNA.B",
            "SYNA-B|SYNA-B",
            "syna|SYNA",
            "SYN|SYN",
    })
    void form4RealIssuerSymbolComesThroughUnchanged(String raw, String expected) {
        List<Form4Transaction> tx =
                form4WithIssuerBlock("<issuerTradingSymbol>" + raw + "</issuerTradingSymbol>");
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).ticker()).isEqualTo(expected);
    }

    /** No symbol element at all: empty ticker, transaction still emitted (unchanged behaviour). */
    @Test void form4MissingIssuerTradingSymbolElementYieldsEmptyTicker() {
        List<Form4Transaction> tx = form4WithIssuerBlock("");
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).ticker()).isEmpty();
        assertThat(tx.get(0).shares()).isEqualByComparingTo("1000");
    }

    // Pre-2023 filing shape: no aff10b5One element, no postTransactionAmounts, no owner CIK, no
    // price — the new fields degrade to null/empty (aff10b5One null means UNKNOWN, never false).
    @Test void form4LegacyFilingWithoutNewFieldsYieldsNulls() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Cook Timothy (CIK 0000000001)"],
                         "file_date":"2025-05-05","file_type":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>Cook Timothy</rptOwnerName></reportingOwnerId></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-05-05</value></transactionDate>
                        <transactionCoding><transactionCode>G</transactionCode></transactionCoding>
                        <transactionAmounts>
                          <transactionShares><value>1000</value></transactionShares>
                        </transactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        Form4Transaction t = tx.get(0);
        assertThat(t.price()).isNull();
        assertThat(t.dollarValue()).isEqualByComparingTo("0"); // missing price still yields 0, unchanged
        assertThat(t.sharesOwnedFollowing()).isNull();
        assertThat(t.aff10b5One()).isNull();
        assertThat(t.filerCik()).isEmpty();
    }

    // An explicit unchecked 10b5-1 box must come back as FALSE — distinguishable from the
    // absent-element null above.
    @Test void form4ExplicitUncheckedAff10b5OneIsFalse() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Cook Timothy (CIK 0000000001)"],
                         "file_date":"2025-05-05","file_type":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <aff10b5One>false</aff10b5One>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>Cook Timothy</rptOwnerName></reportingOwnerId></reportingOwner>
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
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).aff10b5One()).isFalse();
    }

    // form4TransactionsByCik must pass the efts `ciks` entity filter and parse identically to the
    // market-wide variant (same pipeline).
    @Test void form4TransactionsByCikFiltersOnEftsCiksParam() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .withQueryParam("ciks", equalTo("0000320193"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Cook Timothy (CIK 0000000001)"],
                         "file_date":"2025-05-05","file_type":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <aff10b5One>0</aff10b5One>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerCik>0001214156</rptOwnerCik>
                        <rptOwnerName>Cook Timothy</rptOwnerName></reportingOwnerId></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-05-05</value></transactionDate>
                        <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                        <transactionAmounts>
                          <transactionShares><value>1000</value></transactionShares>
                          <transactionPricePerShare><value>190.00</value></transactionPricePerShare>
                        </transactionAmounts>
                        <postTransactionAmounts>
                          <sharesOwnedFollowingTransaction><value>2000</value></sharesOwnedFollowingTransaction>
                        </postTransactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        EdgarSearchService.Form4Result result =
                s.form4TransactionsByCik("0000320193", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100);
        assertThat(result.transactions()).hasSize(1);
        Form4Transaction t = result.transactions().get(0);
        assertThat(t.ticker()).isEqualTo("AAPL");
        assertThat(t.filerCik()).isEqualTo("0001214156");
        assertThat(t.sharesOwnedFollowing()).isEqualByComparingTo("2000");
        assertThat(t.aff10b5One()).isFalse();
        // Two searches per call (caller window, then the late-filing pad) — the entity filter must
        // be on BOTH, or the pad would leak market-wide filings into a per-company answer.
        wm.verify(2, getRequestedFor(urlPathEqualTo("/LATEST/search-index")).withQueryParam("ciks", equalTo("0000320193")));
        wm.verify(0, getRequestedFor(urlPathEqualTo("/LATEST/search-index")).withoutQueryParam("ciks"));
    }

    // The market-wide variant must NOT send an entity filter — and the two variants must not
    // share a cache entry.
    @Test void marketWideForm4SendsNoCiksParam() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("{\"hits\":{\"hits\":[]}}")));
        EdgarSearchService s = svc();
        s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100);
        s.form4TransactionsByCik("0000320193", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100);
        // Two searches per call (caller window + late-filing pad), so 2 of each shape.
        wm.verify(2, getRequestedFor(urlPathEqualTo("/LATEST/search-index")).withoutQueryParam("ciks"));
        wm.verify(2, getRequestedFor(urlPathEqualTo("/LATEST/search-index")).withQueryParam("ciks", equalTo("0000320193")));
    }

    // Truncation on the LIMIT path (a): a multi-transaction filing fills the limit before the
    // hit list is exhausted — the remaining hit is never fetched and the result MUST be marked
    // truncated (a consumer must never mistake the cut-off window for the complete history).
    @Test void form4LimitBreakWithHitsRemainingMarksTruncated() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000001:f1.xml","_source":{"ciks":["1"],"display_names":["A"],"file_date":"2025-05-01","file_type":"4"}},
                      {"_id":"0000320193-25-000002:f2.xml","_source":{"ciks":["1"],"display_names":["B"],"file_date":"2025-05-02","file_type":"4"}}
                    ]}}
                    """)));
        // f1 carries THREE transactions — with limit=3 the loop breaks before ever fetching f2.
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/1/000032019325000001/f1.xml"))
                .willReturn(aResponse().withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>X</rptOwnerName></reportingOwnerId></reportingOwner>
                      <nonDerivativeTable>
                        <nonDerivativeTransaction>
                          <transactionDate><value>2025-05-01</value></transactionDate>
                          <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                          <transactionAmounts><transactionShares><value>1</value></transactionShares>
                            <transactionPricePerShare><value>1</value></transactionPricePerShare></transactionAmounts>
                        </nonDerivativeTransaction>
                        <nonDerivativeTransaction>
                          <transactionDate><value>2025-05-01</value></transactionDate>
                          <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                          <transactionAmounts><transactionShares><value>2</value></transactionShares>
                            <transactionPricePerShare><value>1</value></transactionPricePerShare></transactionAmounts>
                        </nonDerivativeTransaction>
                        <nonDerivativeTransaction>
                          <transactionDate><value>2025-05-01</value></transactionDate>
                          <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                          <transactionAmounts><transactionShares><value>3</value></transactionShares>
                            <transactionPricePerShare><value>1</value></transactionPricePerShare></transactionAmounts>
                        </nonDerivativeTransaction>
                      </nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        EdgarSearchService.Form4Result result =
                s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 3);
        assertThat(result.transactions()).hasSize(3);
        assertThat(result.truncated()).isTrue();
        // hits.size()=2 < limit=3, so path (b) does not apply — this asserts the limit-break path.
        wm.verify(0, getRequestedFor(urlPathEqualTo("/Archives/edgar/data/1/000032019325000002/f2.xml")));
    }

    // Truncation on the LIMIT path (b): the search returned a full limit-sized hit list — the
    // search itself was cut, more filings may exist, so the result is truncated even though every
    // fetched filing was parsed to completion.
    @Test void form4ExactlyLimitSizedHitListMarksTruncated() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("""
                    {"hits":{"total":{"value":50},"hits":[
                      {"_id":"0000320193-25-000001:f1.xml","_source":{"ciks":["1"],"display_names":["A"],"file_date":"2025-05-01","file_type":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/1/000032019325000001/f1.xml"))
                .willReturn(aResponse().withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>X</rptOwnerName></reportingOwnerId></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-05-01</value></transactionDate>
                        <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                        <transactionAmounts><transactionShares><value>1</value></transactionShares>
                          <transactionPricePerShare><value>1</value></transactionPricePerShare></transactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        EdgarSearchService.Form4Result result =
                s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 1);
        assertThat(result.transactions()).hasSize(1);
        assertThat(result.truncated()).isTrue();
    }

    // Control: fewer hits than the limit and no deadline → truncated stays false.
    @Test void form4UnderLimitIsNotTruncated() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000001:f1.xml","_source":{"ciks":["1"],"display_names":["A"],"file_date":"2025-05-01","file_type":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/1/000032019325000001/f1.xml"))
                .willReturn(aResponse().withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>X</rptOwnerName></reportingOwnerId></reportingOwner>
                      <nonDerivativeTable><nonDerivativeTransaction>
                        <transactionDate><value>2025-05-01</value></transactionDate>
                        <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                        <transactionAmounts><transactionShares><value>1</value></transactionShares>
                          <transactionPricePerShare><value>1</value></transactionPricePerShare></transactionAmounts>
                      </nonDerivativeTransaction></nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        EdgarSearchService.Form4Result result =
                s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100);
        assertThat(result.transactions()).hasSize(1);
        assertThat(result.truncated()).isFalse();
    }

    // Fail-soft price + per-row sharesOwnedFollowing + garbage 10b5-1 value: a footnote-only or
    // empty-<value/> transactionPricePerShare yields price=null/dollarValue=0 but KEEPS the
    // transaction (intentional change — the old code skipped the whole filing on an unparsable
    // price); each row keeps its own sharesOwnedFollowing (none bleeds into the row without one);
    // a garbage aff10b5One value degrades to null (unknown), not false.
    @Test void form4UnparsablePricePerRowOwnedAndGarbageFlagDegradeGracefully() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("""
                    {"hits":{"hits":[
                      {"_id":"0000320193-25-000001:f1.xml","_source":{"ciks":["1"],"display_names":["A"],"file_date":"2025-05-06","file_type":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/1/000032019325000001/f1.xml"))
                .willReturn(aResponse().withHeader("Content-Type","application/xml").withBody("""
                    <ownershipDocument>
                      <aff10b5One>maybe</aff10b5One>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>X</rptOwnerName></reportingOwnerId></reportingOwner>
                      <nonDerivativeTable>
                        <nonDerivativeTransaction>
                          <transactionDate><value>2025-05-05</value></transactionDate>
                          <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                          <transactionAmounts>
                            <transactionShares><value>100</value></transactionShares>
                            <transactionPricePerShare><footnoteId id="F1"/></transactionPricePerShare>
                          </transactionAmounts>
                          <postTransactionAmounts>
                            <sharesOwnedFollowingTransaction><value>1100</value></sharesOwnedFollowingTransaction>
                          </postTransactionAmounts>
                        </nonDerivativeTransaction>
                        <nonDerivativeTransaction>
                          <transactionDate><value>2025-05-06</value></transactionDate>
                          <transactionCoding><transactionCode>S</transactionCode></transactionCoding>
                          <transactionAmounts>
                            <transactionShares><value>50</value></transactionShares>
                            <transactionPricePerShare><value></value></transactionPricePerShare>
                          </transactionAmounts>
                          <postTransactionAmounts>
                            <sharesOwnedFollowingTransaction><value>1050</value></sharesOwnedFollowingTransaction>
                          </postTransactionAmounts>
                        </nonDerivativeTransaction>
                        <nonDerivativeTransaction>
                          <transactionDate><value>2025-05-07</value></transactionDate>
                          <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                          <transactionAmounts>
                            <transactionShares><value>10</value></transactionShares>
                            <transactionPricePerShare><value>200.00</value></transactionPricePerShare>
                          </transactionAmounts>
                        </nonDerivativeTransaction>
                      </nonDerivativeTable>
                    </ownershipDocument>
                    """)));
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).transactions();
        assertThat(tx).hasSize(3);
        // row 1: footnote-only price
        assertThat(tx.get(0).price()).isNull();
        assertThat(tx.get(0).dollarValue()).isEqualByComparingTo("0");
        assertThat(tx.get(0).sharesOwnedFollowing()).isEqualByComparingTo("1100");
        // row 2: empty <value/> price; own sharesOwnedFollowing, not row 1's
        assertThat(tx.get(1).price()).isNull();
        assertThat(tx.get(1).dollarValue()).isEqualByComparingTo("0");
        assertThat(tx.get(1).sharesOwnedFollowing()).isEqualByComparingTo("1050");
        // row 3: normal price; NO postTransactionAmounts → null (no bleed from rows 1/2)
        assertThat(tx.get(2).price()).isEqualByComparingTo("200.00");
        assertThat(tx.get(2).dollarValue()).isEqualByComparingTo("2000");
        assertThat(tx.get(2).sharesOwnedFollowing()).isNull();
        // garbage checkbox value → unknown (null), never coerced to a boolean
        assertThat(tx).allSatisfy(t -> assertThat(t.aff10b5One()).isNull());
    }

    // M-F9: transaction date outside [from,to] must be filtered even though the filing itself
    // was filed inside the window.
    @Test void transactionOutsideWindowFilteredEvenWhenFiledInside() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
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
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        // Filing was filed 2025-05-10 (inside the requested window) but the actual transaction
        // happened 2025-01-15 (well outside it) — must be filtered out.
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100).transactions();
        assertThat(tx).isEmpty();
    }

    // M-F9: a filing dated just past `to` but within the widened search window, whose
    // TRANSACTION date is inside [from,to], must still be returned (late-filed-but-in-window).
    @Test void lateFiledTransactionInsideWindowIsIncluded() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
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
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        // Filed 2025-06-05, 5 days after the [from,to]=[..,2025-05-31] window closes — the search
        // window is widened by 10 days, so the filing is still found; its transaction (2025-05-28)
        // is inside [from,to].
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).code()).isEqualTo("S");
    }

    // Lows: 4/A amendments must be included — the root form `forms=4` already carries them on the
    // wire, verified live (see rootFormSearchStillYieldsAmendmentHits) — with the `form` field
    // exposing the amendment so callers can tell a 4 from a 4/A.
    @Test void amendmentFormIsIncludedWithFormField() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
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
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).form()).isEqualTo("4/A");
    }

    // M-F10: sequential per-hit archive GETs are throttled (110ms spacing) via the injected
    // Sleeper — asserts sleep() is invoked once per gap between hits (n-1 times for n hits).
    //
    // BUG-S1a note: this used to run on System::currentTimeMillis and assert a flat 110ms per
    // gap, which is the FIXED-DELAY contract, not the rate-limit one. It now runs on the
    // deterministic fake clock with a ZERO-cost fetch — the case in which the remainder of the
    // window IS the whole window, so the historical 110/110 expectation is preserved exactly
    // while no longer depending on how fast WireMock answered. The remainder arithmetic for a
    // fetch that actually consumes time is pinned by
    // throttleSleepsOnlyTheRemainderOfTheWindowAfterASlowFetch below.
    @Test void form4ArchiveFetchesAreThrottled() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
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
        EdgarSearchService s = pacedSvc(new FakeClock(), 0L, sleeps);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 100).transactions();
        assertThat(tx).hasSize(3);
        // 4 gaps, not 2: the two EFTS searches draw on the SAME budget as the three archive GETs
        // (fix round 2, finding 3), so the sequence is search, search, GET, GET, GET — four gaps.
        // With a zero-cost fetch every gap is the whole window.
        assertThat(sleeps).hasSize(4);
        assertThat(sleeps).allMatch(ms -> ms == 110L);
    }

    // M-F10: an aggregate deadline caps the sequential archive GETs; on deadline the result is
    // partial AND marked truncated.
    @Test void form4DeadlineTruncatesAndMarksResult() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
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
                wmClient(),
                wmClient(),
                wm.baseUrl(), 3600L, now, (EdgarSearchService.Sleeper) ms -> {}, 5L * 1024 * 1024, TICKERS);
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
                .withQueryParam("forms", equalTo("4"))
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
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
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
                .withQueryParam("forms", equalTo("4"))
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
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        List<Form4Transaction> tx = s.form4Transactions(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).ticker()).isEqualTo("NPB");
    }

    @Test void form4WithDoctypeExternalEntityNeverResolvesEntity() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
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
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
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
                .withQueryParam("forms", equalTo("4"))
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
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
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
                wmClient(), wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);

        var ft = svc.filingText(wm.baseUrl() + "/Archives/edgar/data/1/x.htm");

        assertThat(ft.sectionFound()).isTrue();
        assertThat(ft.text()).contains("SUMMARY TERM SHEET").contains("$52.00");
        assertThat(ft.charCount()).isEqualTo(ft.text().length());
        assertThat(ft.sourceUrl()).endsWith("/Archives/edgar/data/1/x.htm");
    }

    @Test void filingTextRejectsNonArchiveUrl() {
        var svc = new EdgarSearchService(
                wmClient(), wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        assertThatThrownBy(() -> svc.filingText("https://evil.example/secret"))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void filingTextEmptyDocumentIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/2/empty.htm"))
                .willReturn(aResponse().withBody("")));
        var svc = new EdgarSearchService(
                wmClient(), wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
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
                wmClient(),
                wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis,
                (EdgarSearchService.Sleeper) ms -> {}, 100L, TICKERS); // 100-byte cap, body is 200 bytes
        assertThatThrownBy(() -> svc.filingText(wm.baseUrl() + "/Archives/edgar/data/3/big.htm"))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void filingTextUnderSizeCapStillWorks() {
        String body = "<p>SUMMARY TERM SHEET</p><p>ok</p>";
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/4/small.htm"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody(body)));
        var svc = new EdgarSearchService(
                wmClient(),
                wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis,
                (EdgarSearchService.Sleeper) ms -> {}, 1024L, TICKERS);
        var ft = svc.filingText(wm.baseUrl() + "/Archives/edgar/data/4/small.htm");
        assertThat(ft.sectionFound()).isTrue();
    }

    // ---- A4: an oversized filing must be distinguishable from a dead source -------------------
    // Production symptom this pins: Dracul logged "Agora unreachable for get_filing_text" for six
    // DEFM14A merger proxies every single run, indistinguishable from a transport outage.

    /**
     * The Content-Length pre-check path. It cannot be driven through WireMock — Jetty chunks the
     * response and drops an explicitly stubbed Content-Length — so the response is supplied by a
     * request factory that really does advertise one. Note this pre-check is NOT the path that
     * fires against the live SEC archive: a GET of the failing DEFM14A documents comes back
     * without a Content-Length, so production traffic lands in the bounded-read check below.
     * The pre-check only saves the download when an upstream does advertise a length.
     */
    @Test void filingTextOverSizeCapViaContentLengthReportsTooLargeKindAndToken() {
        byte[] body = "x".repeat(200).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var response = new org.springframework.mock.http.client.MockClientHttpResponse(
                body, org.springframework.http.HttpStatus.OK);
        response.getHeaders().setContentLength(9_000_000L);
        RestClient advertising = RestClient.builder().requestFactory((uri, method) -> {
            var req = new org.springframework.mock.http.client.MockClientHttpRequest(method, uri);
            req.setResponse(response);
            return req;
        }).build();
        var svc = new EdgarSearchService(
                wmClient(), advertising,
                wm.baseUrl(), 3600L, System::currentTimeMillis,
                (EdgarSearchService.Sleeper) ms -> {}, 100L, TICKERS);
        assertThatThrownBy(() -> svc.filingText(wm.baseUrl() + "/Archives/edgar/data/5/big.htm"))
                .isInstanceOf(MarketDataException.class)
                .satisfies(e -> {
                    var m = (MarketDataException) e;
                    assertThat(m.kind()).isEqualTo(MarketDataException.Kind.TOO_LARGE);
                    assertThat(m.getMessage()).startsWith("filing_too_large:");
                    // the operator must see BOTH numbers and the knob that changes them
                    assertThat(m.getMessage()).contains("9000000").contains("100")
                            .contains("agora.data.edgar.max-filing-bytes");
                });
    }

    /** The post-read bounded-read path: no Content-Length on the wire (chunked response). */
    @Test void filingTextOverSizeCapWithoutContentLengthReportsTooLargeKindAndToken() {
        String body = "x".repeat(200);
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/6/chunked.htm"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html")
                        .withBody(body).withChunkedDribbleDelay(4, 10)));
        var svc = new EdgarSearchService(
                wmClient(),
                wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis,
                (EdgarSearchService.Sleeper) ms -> {}, 100L, TICKERS);
        assertThatThrownBy(() -> svc.filingText(wm.baseUrl() + "/Archives/edgar/data/6/chunked.htm"))
                .isInstanceOf(MarketDataException.class)
                .satisfies(e -> {
                    var m = (MarketDataException) e;
                    assertThat(m.kind()).isEqualTo(MarketDataException.Kind.TOO_LARGE);
                    assertThat(m.getMessage()).startsWith("filing_too_large:");
                    assertThat(m.getMessage()).contains("agora.data.edgar.max-filing-bytes");
                });
    }

    // ---- C1: the memory ceiling must be a property of the service, not of who calls it -------
    // A get_filing_text at the 32 MiB cap holds byte[] + decoded String + extractor intermediates
    // (~100-160 MiB transient). Tomcat is unconfigured, so 200 request threads could run that
    // concurrently against a heap the JVM sized from the LXC HOST's memory, not the container's.
    // What keeps production alive today is an accident on the consumer side (Dracul's AgoraClient
    // serialises tool calls), which a second consumer or a second replica removes silently.

    /** {@code permits} concurrent large-document fetches, {@code queueMs} wait for a permit. */
    private EdgarSearchService boundedFetcher(int permits, long queueMs) {
        return new EdgarSearchService(
                wmClient(),
                wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis,
                (EdgarSearchService.Sleeper) ms -> {}, 1024L, TICKERS, permits, queueMs);
    }

    private static void stubSlowFiling(String path, int delayMs) {
        wm.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse()
                .withHeader("Content-Type", "text/html").withFixedDelay(delayMs)
                .withBody("<p>SUMMARY TERM SHEET</p><p>ok</p>")));
    }

    @Test void concurrentFilingFetchesBeyondTheBoundAreRefusedRatherThanBuffered() throws Exception {
        stubSlowFiling("/Archives/edgar/data/8/a.htm", 1500);
        stubSlowFiling("/Archives/edgar/data/8/b.htm", 1500);
        var svc = boundedFetcher(1, 50);   // one in flight; a queued caller waits at most 50ms

        var started = new java.util.concurrent.CountDownLatch(1);
        var holder = new Thread(() -> {
            started.countDown();
            try { svc.filingText(wm.baseUrl() + "/Archives/edgar/data/8/a.htm"); } catch (Exception ignored) { }
        });
        holder.start();
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);   // the holder is now inside the fetch, owning the only permit

        assertThatThrownBy(() -> svc.filingText(wm.baseUrl() + "/Archives/edgar/data/8/b.htm"))
                .isInstanceOf(MarketDataException.class)
                .satisfies(e -> {
                    var m = (MarketDataException) e;
                    assertThat(m.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE);
                    // a stable machine token, distinct from filing_too_large and from a dead source
                    assertThat(m.getMessage()).startsWith("filing_fetch_busy:");
                    assertThat(m.getMessage()).contains("agora.data.edgar.max-concurrent-filing-fetches");
                });
        holder.join(10_000);
    }

    /** The bound must not serialise everything — up to the bound, fetches run concurrently. */
    @Test void concurrentFilingFetchesUpToTheBoundAllSucceed() throws Exception {
        stubSlowFiling("/Archives/edgar/data/9/a.htm", 400);
        stubSlowFiling("/Archives/edgar/data/9/b.htm", 400);
        var svc = boundedFetcher(2, 50);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> svc.filingText(wm.baseUrl() + "/Archives/edgar/data/9/a.htm"));
            var b = pool.submit(() -> svc.filingText(wm.baseUrl() + "/Archives/edgar/data/9/b.htm"));
            assertThat(a.get(10, java.util.concurrent.TimeUnit.SECONDS).sectionFound()).isTrue();
            assertThat(b.get(10, java.util.concurrent.TimeUnit.SECONDS).sectionFound()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    /** A failed fetch must give its permit back, or the bound degrades into a permanent lockout. */
    @Test void aFailedFilingFetchReleasesItsPermit() {
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/10/down.htm"))
                .willReturn(aResponse().withStatus(503)));
        stubSlowFiling("/Archives/edgar/data/10/ok.htm", 0);
        var svc = boundedFetcher(1, 50);
        assertThatThrownBy(() -> svc.filingText(wm.baseUrl() + "/Archives/edgar/data/10/down.htm"))
                .isInstanceOf(MarketDataException.class);
        assertThat(svc.filingText(wm.baseUrl() + "/Archives/edgar/data/10/ok.htm").sectionFound()).isTrue();
    }

    /**
     * The default bound, with its arithmetic. Worst case per in-flight fetch at the 32 MiB cap:
     * byte[] 32 MiB + decoded String 32-64 MiB + extractor intermediates 32-64 MiB = 96-160 MiB.
     * 8 x 160 MiB = 1.25 GiB peak — about 8% of the 15.49 GiB heap the JVM sizes itself to on the
     * production host, and of the 16 GiB the container shares with its neighbours. Unbounded it
     * was 200 Tomcat threads x 160 MiB = 31 GiB, i.e. an OOM kill of the whole container at
     * roughly 110 concurrent calls, with no OutOfMemoryError in the log.
     */
    @Test void defaultConcurrentFilingFetchBoundIsEight() {
        assertThat(EdgarSearchService.DEFAULT_MAX_CONCURRENT_FILING_FETCHES).isEqualTo(8);
        // 8 x the 32 MiB cap must stay a small fraction of a 16 GiB container
        assertThat(EdgarSearchService.DEFAULT_MAX_CONCURRENT_FILING_FETCHES
                * EdgarSearchService.DEFAULT_MAX_FILING_BYTES * 5)
                .isLessThan(2L * 1024 * 1024 * 1024);
    }

    /** The bound must be operator-tunable — a hard-compiled ceiling cannot be raised in an
     *  incident, and a second consumer or replica is exactly when it needs raising. */
    @Test void concurrentFilingFetchBoundIsAConfigProperty() throws Exception {
        String yaml;
        try (var in = getClass().getResourceAsStream("/application.yaml")) {
            yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(yaml).contains(
                "max-concurrent-filing-fetches: ${AGORA_DATA_EDGAR_MAX_CONCURRENT_FILING_FETCHES:8}");
    }

    /** A genuine transport failure must NOT wear the too-large token. */
    @Test void filingTextTransportFailureStaysUnavailableAndUntokened() {
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/7/down.htm"))
                .willReturn(aResponse().withStatus(503)));
        var svc = new EdgarSearchService(
                wmClient(),
                wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis,
                (EdgarSearchService.Sleeper) ms -> {}, 1024L, TICKERS);
        assertThatThrownBy(() -> svc.filingText(wm.baseUrl() + "/Archives/edgar/data/7/down.htm"))
                .isInstanceOf(MarketDataException.class)
                .satisfies(e -> {
                    var m = (MarketDataException) e;
                    assertThat(m.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE);
                    assertThat(m.getMessage()).doesNotContain("filing_too_large");
                });
    }

    /**
     * The compiled default must admit a real merger proxy. Measured 2026-08-04 over the 40 most
     * recent DEFM14A primary documents (EFTS, 2026-02-01..2026-08-01): median 3.53 MB, p90
     * 10.21 MB, max 24.93 MB, and 13 of 40 above the old 5 MB cap. 32 MiB clears the measured
     * maximum with headroom.
     */
    @Test void defaultFilingSizeCapAdmitsTheMeasuredDefm14aMaximum() {
        assertThat(EdgarSearchService.DEFAULT_MAX_FILING_BYTES).isEqualTo(32L * 1024 * 1024);
        assertThat(EdgarSearchService.DEFAULT_MAX_FILING_BYTES).isGreaterThan(24_936_966L);
    }

    /** The cap must be operator-tunable, not hard-compiled (it was, before A4). */
    @Test void filingSizeCapIsAConfigProperty() throws Exception {
        String yaml;
        try (var in = getClass().getResourceAsStream("/application.yaml")) {
            yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(yaml).contains("max-filing-bytes: ${AGORA_DATA_EDGAR_MAX_FILING_BYTES:33554432}");
    }

    // ---- A3: the row cap must reach 1000 and truncation must stay exact at that bound ---------

    @Test void searchPaginatesUpToTheNewThousandRowBound() {
        stubPages(5000, 10, 100);
        var svc = new EdgarSearchService(
                wmClient(),
                wmClient(),
                "https://www.sec.gov", 3600L, System::currentTimeMillis,
                (EdgarSearchService.Sleeper) ms -> {}, 1024L, TICKERS);
        List<FilingHit> hits = svc.search(List.of("4"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 1000);
        assertThat(hits).hasSize(1000);
    }

    @Test void form4TruncatedWhenSearchFillsTheThousandRowBound() {
        stubPages(5000, 10, 100);
        var svc = new EdgarSearchService(
                wmClient(),
                wmClient(),
                "https://www.sec.gov", 3600L, System::currentTimeMillis,
                (EdgarSearchService.Sleeper) ms -> {}, 1024L, TICKERS);
        var r = svc.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 1000);
        assertThat(r.truncated()).isTrue();
    }

    @Test void form4NotTruncatedJustBelowTheThousandRowBound() {
        stubPages(999, 9, 100);
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).withQueryParam("from", equalTo("900"))
                .willReturn(okJson(page(999, 900, 99))));
        var svc = new EdgarSearchService(
                wmClient(),
                wmClient(),
                "https://www.sec.gov", 3600L, System::currentTimeMillis,
                (EdgarSearchService.Sleeper) ms -> {}, 1024L, TICKERS);
        var r = svc.form4Transactions(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 1000);
        assertThat(r.truncated()).isFalse();
    }

    // ---- B1: the HARD_FETCH_CAP stop must be REPORTED, not only logged ------------------------
    // MAX_LIMIT == HARD_FETCH_CAP == 1000 leaves zero headroom, so `hits.size() >= limit` is the
    // only truncation signal and it needs exactly 1000 rows. A single hit dropped by parseHit's
    // null return or by the per-hit catch — anywhere across the 10 pages — yields 999 and the
    // window is reported COMPLETE while EFTS holds tens of thousands more filings.

    /** A no-op sleeper + frozen clock: isolates the cap flag from the Form-4 throttle/deadline. */
    private EdgarSearchService cappedProbe() {
        return new EdgarSearchService(
                wmClient(),
                wmClient(),
                "https://www.sec.gov", 3600L, () -> 0L,
                (EdgarSearchService.Sleeper) ms -> {}, 1024L, TICKERS);
    }

    @Test void searchStoppedByTheHardFetchCapReportsCappedEvenWithADroppedHit() {
        stubPagesWithOneMalformed(50_000, 10, 100, 5);
        var r = cappedProbe().searchResult(List.of("8-K"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 1000);
        assertThat(r.hits()).hasSize(999);   // one hit dropped -> the row count cannot signal it
        assertThat(r.capped()).isTrue();
    }

    @Test void form4TruncatedWhenTheHardFetchCapCutTheSearchDespiteADroppedHit() {
        stubPagesWithOneMalformed(50_000, 10, 100, 5);
        var r = cappedProbe().form4Transactions(
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 1000);
        assertThat(r.truncated()).isTrue();
    }

    /** An exhausted window must stay uncapped — the flag must not be permanently on. */
    @Test void searchThatExhaustsTheWindowIsNotCapped() {
        stubPages(300, 3, 100);
        var r = cappedProbe().searchResult(List.of("8-K"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 1000);
        assertThat(r.hits()).hasSize(300);
        assertThat(r.capped()).isFalse();
    }

    /**
     * EFTS answers a too-deep window with HTTP 200 and an OpenSearch error body, verified live
     * 2026-08-04 against {@code from=10000}:
     * {@code {"errorType":"ResponseError","errorMessage":"search_phase_execution_exception: ...
     * Result window is too large, from + size must be less than or equal to: [10000] ..."}}.
     * It carries no {@code hits} key, so the paging loop would otherwise read it as an exhausted
     * window and report the cut as a complete one.
     */
    @Test void eftsErrorBodyIsReportedAsCappedNotAsAnExhaustedWindow() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).withQueryParam("from", equalTo("0"))
                .willReturn(okJson(page(50_000, 0, 100))));
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).withQueryParam("from", equalTo("100"))
                .willReturn(okJson("""
                    {"errorType":"ResponseError","errorMessage":"search_phase_execution_exception: \
                    [illegal_argument_exception] Reason: Result window is too large, from + size \
                    must be less than or equal to: [10000] but was [10100]."}""")));
        var r = cappedProbe().searchResult(List.of("8-K"), null,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), 1000);
        assertThat(r.hits()).hasSize(100);
        assertThat(r.capped()).isTrue();
    }

    /** The cap is the tools' advertised ceiling, so it must be readable from them — the javadoc
     *  in search_filings / get_form4_transactions claimed a coupling that did not exist in code. */
    @Test void hardFetchCapIsPubliclyReadable() {
        assertThat(EdgarSearchService.HARD_FETCH_CAP).isEqualTo(1000);
    }

    // ---------------------------------------------------------------------------------------
    // The EFTS `forms` wire contract, measured live 2026-08-04 (SEC User-Agent, window
    // 2026-07-20..2026-07-27 unless noted). Every number below is a real EFTS
    // hits.total.value, not an invented fixture:
    //
    //   forms=4                       -> 1697   (page carried file_type 4 AND 4/A)
    //   forms=4/A                     ->   38
    //   forms=4,4/A                   ->   38   <-- the defect: intersection, not union
    //   forms=4/A,4  (order swapped)  ->   38
    //   forms=4&forms=4/A (repeated)  ->   38
    //   forms=3,4/A                   ->    0   <-- proves the /A token is a global narrowing
    //   forms=3,4,4/A                 ->   38
    //   forms=3                       ->  312
    //   forms=3,4                     -> 2009   == 312 + 1697, so CSV IS a correct union
    //                                            across ROOT forms
    //
    // Semantics: `forms` selects ROOT forms and always includes their amendments (the
    // aggregations.form_filter bucket key for a 4/A hit is "4"). Adding an explicit "X/A"
    // token intersects the whole query down to that amendment type. There is therefore no
    // encoding that unions 4 and 4/A — none is needed, because `forms=4` already IS that
    // union.
    // ---------------------------------------------------------------------------------------

    /**
     * The market-wide Form-4 search must ask for the ROOT form only. Sending "4,4/A" collapsed
     * a 1,697-filing window to the 38 amendments (measured live, see the table above) — the
     * production symptom was strigoi-insider returning 0 items for 10 straight days.
     */
    @Test void form4SearchesTheRootFormOnlyNeverTheAmendmentToken() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}")));
        svc().form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100);
        wm.verify(getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4")));
        wm.verify(0, getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", containing("/A")));
    }

    /**
     * Order of the two searches is the whole point: EFTS returns file_date DESCENDING and the
     * fetch budget (HARD_FETCH_CAP + the 30s aggregate deadline) is far smaller than a
     * market-wide window, so whichever range is searched FIRST is the only one that gets read.
     * Searching the padded range first spends the entire budget inside the late-filing pad.
     *
     * <p>Measured live 2026-08-04 on caller window 2026-07-20..2026-07-27 (a full end-to-end
     * replay of this method's loop, real EFTS + real archive GETs):
     * padded-range-first yielded 0 in-window transactions; caller-window-first yielded 187.
     */
    @Test void form4SearchesTheCallerWindowBeforeTheLateFilingPad() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}")));
        svc().form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100);
        // 1) the caller's exact window, unpadded
        wm.verify(getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-01"))
                .withQueryParam("enddt", equalTo("2025-05-31")));
        // 2) only then the late-filing pad, which starts the day AFTER the window closes
        wm.verify(getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-06-01"))
                .withQueryParam("enddt", equalTo("2025-06-10")));
    }

    /**
     * The backward pad is provably dead weight and must not be searched: a Form 4's
     * transactionDate never exceeds its file_date, so a filing filed before {@code from} cannot
     * carry an in-window transaction. Measured live 2026-08-04 over 100 Form 4s filed
     * 2026-07-10..2026-07-17: 162 of 162 non-derivative transactions had
     * file_date - transactionDate >= 0; none was ahead.
     */
    @Test void form4DoesNotSearchBeforeTheCallerWindow() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}")));
        svc().form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100);
        wm.verify(0, getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-04-21")));
    }

    /**
     * Limit and truncation must stay coherent across the two searches: once the caller-window
     * search has filled the limit, the pad search is not run at all and the result says so.
     */
    @Test void form4LimitFilledByTheWindowSkipsThePadAndReportsTruncated() {
        // total 5000 with 100-hit pages: the window search alone fills limit=100
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-01"))
                .willReturn(okJson(page(5000, 0, 100))));
        var r = svc().form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100);
        assertThat(r.truncated()).isTrue();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-06-01")));
    }

    /** A cap hit inside the PAD search is just as much a cut as one in the window search. */
    @Test void form4ReportsTruncatedWhenThePadSearchIsCapped() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-01"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}")));
        // the pad search answers with EFTS's error body -> capped, and no hits to fetch
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-06-01"))
                .willReturn(okJson("""
                    {"errorType":"ResponseError","errorMessage":"search_phase_execution_exception"}""")));
        var r = svc().form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 1000);
        assertThat(r.transactions()).isEmpty();
        assertThat(r.truncated()).isTrue();
    }

    /** The two searches cover disjoint date ranges, but a filing must never be fetched twice
     *  even if EFTS were to return it in both. */
    @Test void form4DeduplicatesAFilingReturnedByBothSearches() {
        String hit = """
            {"hits":{"total":{"value":1},"hits":[
              {"_id":"0000320193-25-000099:form4.xml","_source":{
                 "ciks":["0000320193"],
                 "display_names":["Cook Timothy (CIK 0000000001)"],
                 "file_date":"2025-05-05","file_type":"4"}}
            ]}}""";
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).willReturn(okJson(hit)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody("""
                    <ownershipDocument>
                      <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                      <reportingOwner><reportingOwnerId><rptOwnerName>Cook Timothy</rptOwnerName></reportingOwnerId></reportingOwner>
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
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        var tx = s.form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100).transactions();
        assertThat(tx).hasSize(1);
    }

    /**
     * A root form already carries its amendments on the wire, so a 4/A hit arrives from
     * {@code forms=4} and must still be emitted with {@code form="4/A"}. Live evidence: a
     * {@code forms=4} page of 100 hits contained 99 file_type "4" and 1 file_type "4/A", and
     * the response's aggregations.form_filter bucket for that hit was keyed "4".
     */
    @Test void rootFormSearchStillYieldsAmendmentHits() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("""
                    {"hits":{"total":{"value":1},"hits":[
                      {"_id":"0000320193-25-000099:form4a.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Cook Timothy (CIK 0000000001)"],
                         "file_date":"2025-05-10","file_type":"4/A"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4a.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody("""
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
        EdgarSearchService s = new EdgarSearchService(wmClient(),
                wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        var tx = s.form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100).transactions();
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).form()).isEqualTo("4/A");
    }

    /** Like {@link #stubPages} but the hit at global index {@code malformedAt} carries an empty
     *  file_date, so parseHit returns null for it and the collected row count falls one short. */
    private static void stubPagesWithOneMalformed(int total, int pages, int size, int malformedAt) {
        for (int p = 0; p < pages; p++) {
            int offset = p * size;
            StringBuilder sb = new StringBuilder("{\"hits\":{\"total\":{\"value\":").append(total)
                    .append("},\"hits\":[");
            for (int i = 0; i < size; i++) {
                int id = offset + i;
                if (i > 0) sb.append(",");
                sb.append("{\"_id\":\"a-").append(id)
                        .append(":d.htm\",\"_source\":{\"display_names\":[\"Filer ").append(id)
                        .append("\"],\"file_date\":\"")
                        .append(id == malformedAt ? "" : "2025-05-01")
                        .append("\",\"file_type\":\"4\"}}");
            }
            sb.append("]}}");
            wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                    .withQueryParam("from", equalTo(String.valueOf(offset)))
                    .willReturn(okJson(sb.toString())));
        }
    }

    /** {@code pages} pages of {@code size} hits each, starting at offset 0, reporting {@code total}. */
    private static void stubPages(int total, int pages, int size) {
        for (int p = 0; p < pages; p++) {
            int offset = p * size;
            wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                    .withQueryParam("from", equalTo(String.valueOf(offset)))
                    .willReturn(okJson(page(total, offset, size))));
        }
    }

    /** An efts page with no {@code ciks} — url stays empty, so no archive GET is made per hit. */
    private static String page(int total, int offset, int size) {
        StringBuilder sb = new StringBuilder("{\"hits\":{\"total\":{\"value\":").append(total)
                .append("},\"hits\":[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(',');
            int n = offset + i;
            sb.append("{\"_id\":\"0000000000-25-").append(String.format("%06d", n))
              .append(":f.xml\",\"_source\":{\"display_names\":[\"Filer ").append(n)
              .append("  (CIK 0000000001)\"],\"file_date\":\"2025-05-02\",\"file_type\":\"4\"}}");
        }
        return sb.append("]}}").toString();
    }

    // ---------------------------------------------------------------------------------------
    // BUG-S19 — EFTS answers 500 intermittently and one transient failure blinded a whole run.
    // Measured on the production container 2026-08-06: 2 of 13 EFTS calls in one hour returned
    // 500, and the SAME `forms=10-12B` query returned both 200 and 500 inside that window, so it
    // is transient rather than query-shaped. All fixtures below are hand-written.
    // ---------------------------------------------------------------------------------------

    /** Records what the retry backoff asked to sleep, so the schedule is pinned by assertion. */
    private final java.util.List<Long> searchSleeps = new java.util.ArrayList<>();

    /** The fake clock backing {@link #retryingSvc()} — advanced by the sleeper and by each request. */
    private FakeClock searchClock;

    private EdgarSearchService retryingSvc() {
        return retryingSvc(0L);
    }

    /**
     * Service whose EFTS client points at WireMock, with a COHERENT fake clock/sleeper pair: the
     * recording sleeper advances the clock by what it was asked to sleep, and each EFTS request
     * advances it by {@code perRequestMs}.
     *
     * <p>That pairing became load-bearing once the shared {@link EdgarRequestPacer} started spacing
     * the EFTS requests too. These tests used to pair a REAL clock with a fake sleeper, so a
     * 250 ms backoff advanced the clock by nothing and the pacer then charged a ~109 ms spacing
     * wait on the next attempt — an artifact of the test harness, not of production, where the
     * sleeper really sleeps and a backoff of 250/750 ms therefore always exceeds the 110 ms
     * spacing and costs a retry no extra wait. With the clock and the sleeper agreeing, the
     * backoff schedules below assert exactly that production relationship.
     *
     * <p>Both clients come from {@link #wmClient()}, i.e. the factory production pins — load-bearing
     * here in particular: on a bare builder the Apache client's own retry strategy would repeat the
     * 429/503 stubs and the request counts asserted below would be measuring it rather than the
     * service's retry.
     */
    private EdgarSearchService retryingSvc(long perRequestMs) {
        searchClock = new FakeClock();
        org.springframework.http.client.ClientHttpRequestInterceptor costsTime =
                (request, body, execution) -> {
                    // advanced BEFORE the call, so a failed attempt costs its time too
                    searchClock.advance(perRequestMs);
                    return execution.execute(request, body);
                };
        return new EdgarSearchService(
                de.visterion.agora.data.DataHttp.clientBuilder(15_000, costsTime).baseUrl(wm.baseUrl()).build(),
                wmClient(), wm.baseUrl(), 3600L, searchClock,
                ms -> { searchSleeps.add(ms); searchClock.advance(ms); },
                5L * 1024 * 1024, TICKERS);
    }

    private static final String ONE_HIT_PAGE = """
            {"hits":{"total":{"value":1},"hits":[
              {"_id":"a-1:d1.htm","_source":{"ciks":["0000000042"],"display_names":["Acme Corp  (ACME)  (CIK 0000000042)"],"file_date":"2025-05-01","file_type":"10-12B"}}
            ]}}""";

    /** A transient 500 on attempt 1 must not blind the caller — attempt 2 answers, no throw. */
    @Test void transientServerErrorIsRetriedAndSucceeds() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).inScenario("efts-flaky")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500).withBody("{\"message\": \"Internal server error\"}"))
                .willSetStateTo("recovered"));
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).inScenario("efts-flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(ONE_HIT_PAGE)));

        List<FilingHit> hits = retryingSvc().search(List.of("10-12B"), null,
                LocalDate.parse("2026-07-17"), LocalDate.parse("2026-08-06"), 100);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().ticker()).isEqualTo("ACME");
        wm.verify(2, getRequestedFor(urlPathEqualTo("/LATEST/search-index")));
    }

    /** Exhausted retries must still be the exact failure the caller gets today. */
    @Test void persistentServerErrorStillThrowsUnavailableAfterExactlyThreeAttempts() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(aResponse().withStatus(500).withBody("{\"message\": \"Internal server error\"}")));

        assertThatThrownBy(() -> retryingSvc().search(List.of("10-12B"), null,
                LocalDate.parse("2026-07-17"), LocalDate.parse("2026-08-06"), 100))
                .isInstanceOf(MarketDataException.class)
                .satisfies(e -> {
                    var m = (MarketDataException) e;
                    assertThat(m.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE);
                    assertThat(m.getMessage()).startsWith("EDGAR search unreachable: ");
                });
        wm.verify(3, getRequestedFor(urlPathEqualTo("/LATEST/search-index")));
    }

    /** 403 is SEC blocking this client. Retrying it makes the block worse — exactly one request. */
    @Test void forbiddenIsNotRetried() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> retryingSvc().search(List.of("10-12B"), null,
                LocalDate.parse("2026-07-17"), LocalDate.parse("2026-08-06"), 100))
                .isInstanceOf(MarketDataException.class);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index")));
        assertThat(searchSleeps).isEmpty();
    }

    /**
     * 429 is deliberately NOT retried: it says this client is already over SEC's published rate,
     * and SEC escalates a sustained excess to a 403 IP block. See the comment on
     * {@code SEARCH_RETRY_BACKOFF_MS} in the service.
     */
    @Test void tooManyRequestsIsNotRetried() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> retryingSvc().search(List.of("10-12B"), null,
                LocalDate.parse("2026-07-17"), LocalDate.parse("2026-08-06"), 100))
                .isInstanceOf(MarketDataException.class);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index")));
        assertThat(searchSleeps).isEmpty();
    }

    /**
     * The backoff schedule is pinned: it goes through the injected Sleeper (so tests never really
     * sleep) and every step stays at or above THROTTLE_MS=110, i.e. a retry can never make this
     * service faster against SEC than the throttle allows.
     */
    @Test void backoffScheduleGoesThroughTheSleeperAndIsPinned() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> retryingSvc().search(List.of("10-12B"), null,
                LocalDate.parse("2026-07-17"), LocalDate.parse("2026-08-06"), 100))
                .isInstanceOf(MarketDataException.class);
        assertThat(searchSleeps).containsExactly(250L, 750L);
        assertThat(searchSleeps).allMatch(ms -> ms >= 110L);
    }

    /** A transport-level failure (connection reset before any status line) is retryable too. */
    @Test void transportFailureIsRetriedAndSucceeds() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).inScenario("efts-reset")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("recovered"));
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).inScenario("efts-reset")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(ONE_HIT_PAGE)));

        assertThat(retryingSvc().search(List.of("10-12B"), null,
                LocalDate.parse("2026-07-17"), LocalDate.parse("2026-08-06"), 100)).hasSize(1);
        assertThat(searchSleeps).containsExactly(250L);
    }

    /**
     * The retry budget is aggregate over the whole search, not per page — a systematic outage must
     * not cost pages x attempts x backoff. Each EFTS REQUEST costs 1200ms of fake time (rather than
     * every clock reading, which the pacer's own readings would now distort), so page 0's one retry
     * charges 1200ms + 250ms backoff against the 2500ms budget; page 1's failure then finds
     * 1050 - 1200 ms left, i.e. nothing, and is not retried at all.
     */
    @Test void aggregateRetryBudgetStopsRetryingAcrossPages() {
        // page 0: 500 once, then a full 100-hit page reporting more to come
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).withQueryParam("from", equalTo("0"))
                .inScenario("efts-budget")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("page0-ok"));
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).withQueryParam("from", equalTo("0"))
                .inScenario("efts-budget").whenScenarioStateIs("page0-ok")
                .willReturn(okJson(pageOf(0, 100, 300))));
        // page 1: always 500 — with budget left it would be retried twice; here it must not be.
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index")).withQueryParam("from", equalTo("100"))
                .willReturn(aResponse().withStatus(500)));

        var svc = retryingSvc(1200L);
        assertThatThrownBy(() -> svc.search(List.of("10-12B"), null,
                LocalDate.parse("2026-07-17"), LocalDate.parse("2026-08-06"), 300))
                .isInstanceOf(MarketDataException.class);

        wm.verify(2, getRequestedFor(urlPathEqualTo("/LATEST/search-index")).withQueryParam("from", equalTo("0")));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index")).withQueryParam("from", equalTo("100")));
        assertThat(searchSleeps).containsExactly(250L);   // page 1 never got to sleep
    }

    // ---------------------------------------------------------------------------------------
    // BUG-S1a — THROTTLE_MS must SPACE the archive GETs, not delay each one.
    //
    // The old loop slept a flat 110ms BEFORE every fetch, so the spacing between two consecutive
    // archive GETs was 110ms + however long the fetch itself took. The service's own measured
    // note recorded the consequence: ~140 filings read per call at ~190ms each, i.e. ~5.3 req/s
    // against the ~9.1/s the constant declares — while a market-wide 7-day window holds ~1,700
    // Form-4 filings (measured live 2026-08-04).
    //
    // The harness below is a fully deterministic fake. The clock advances ONLY when the fake
    // sleeper sleeps and when the archive client fetches, so "how long a fetch took" is an exact
    // scripted number rather than a measured one — which is what lets these tests assert sleep
    // DURATIONS instead of a sleep count. A count-only assertion is exactly what let the flat
    // delay survive: the old test asserted "n-1 sleeps of 110ms" and the bug satisfied it.
    // ---------------------------------------------------------------------------------------

    /**
     * BUG-S20 regression guard for the ARCHIVE client. A Form-4 archive GET that answers 503 must
     * be made exactly ONCE — {@link EdgarSearchService#parseForm4} swallows the failure and moves
     * on, and there is no retry anywhere in that path.
     *
     * <p>Before the migration this test failed with 2 requests: the archive client of the test
     * constructor was a bare {@code RestClient.builder()}, and Apache HttpClient 5's
     * {@code DefaultHttpRequestRetryStrategy} repeated the GET. Production pins the JDK factory
     * and never did, so the whole Form-4 suite was measuring a client production does not use.
     */
    @Test void aFailedArchiveGetIsNotSilentlyRetriedByTheHttpClient() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("forms", equalTo("4"))
                .willReturn(okJson("""
                    {"hits":{"total":{"value":1},"hits":[
                      {"_id":"0000320193-25-000099:form4.xml","_source":{
                         "ciks":["0000320193"],
                         "display_names":["Cook Timothy (CIK 0000000001)"],
                         "file_date":"2025-05-05","file_type":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml"))
                .willReturn(aResponse().withStatus(503)));

        var s = new EdgarSearchService(wmClient(), wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        var r = s.form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100);

        assertThat(r.transactions()).isEmpty();   // the filing is skipped, never thrown
        wm.verify(1, getRequestedFor(urlPathEqualTo("/Archives/edgar/data/320193/000032019325000099/form4.xml")));
    }

    // ---------------------------------------------------------------------------------------
    // BUG-S1a fix round 2, finding 1 — a day-sized window must read the NEAR end of the pad.
    //
    // SEC gives a filer two business days ("Form 4 must be filed before the end of the second
    // business day following the day on which the subject transaction has been executed",
    // 17 CFR 240.16a-3(g)(1)), so a transaction dated D is mostly reported by filings filed D+1
    // or D+2 — not on D. EFTS sorts file_date DESCENDING, so a single pad search over D+1..D+10
    // hands back the D+10 filings first, whose transactions post-date the window and are all
    // discarded. A day slice could therefore only ever see its same-day-filed minority.
    // ---------------------------------------------------------------------------------------

    /** A Form-4 XML whose single transaction is dated 2025-05-15, i.e. inside a D=15 day slice. */
    private static final String ONE_TX_FORM4_ON_15TH = """
            <ownershipDocument>
              <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
              <reportingOwner><reportingOwnerId><rptOwnerName>X</rptOwnerName></reportingOwnerId></reportingOwner>
              <nonDerivativeTable><nonDerivativeTransaction>
                <transactionDate><value>2025-05-15</value></transactionDate>
                <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                <transactionAmounts><transactionShares><value>1</value></transactionShares>
                  <transactionPricePerShare><value>1</value></transactionPricePerShare></transactionAmounts>
              </nonDerivativeTransaction></nonDerivativeTable>
            </ownershipDocument>
            """;

    /** One filing hit whose accession encodes the day it was filed, so a fetch can be identified. */
    private static String padHit(String accession, String filedDate) {
        return """
            {"_id":"%s:f.xml","_source":{"ciks":["1"],"display_names":["Filer"],
             "file_date":"%s","file_type":"4"}}""".formatted(accession, filedDate);
    }

    /**
     * A market-wide day slice must spend its leftover budget on the filings filed the day AFTER
     * the window (where D's trades are reported), not on the far end of the pad.
     *
     * <p>Asserts WHICH accession was fetched, not how many: a count-based assertion passes on
     * either ordering, which is exactly what let this through. Against the old single descending
     * pad search the D+10 filing was fetched and the D+1 one was not.
     */
    @Test void aDaySlicePadReadsTheFilingsFiledTheNextDayNotTheFarEndOfThePad() {
        // the day slice itself: one filing filed on D
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-15"))
                .withQueryParam("enddt", equalTo("2025-05-15"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":1},\"hits\":["
                        + padHit("0000000001-25-000000", "2025-05-15") + "]}}")));
        // D+1: the filing that actually carries a trade dated D (the two-business-day norm)
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-16"))
                .withQueryParam("enddt", equalTo("2025-05-16"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":1},\"hits\":["
                        + padHit("0000000001-25-000001", "2025-05-16") + "]}}")));
        // the OLD single descending pad search (D+1..D+10 in one go) hands back D+10 FIRST
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-16"))
                .withQueryParam("enddt", equalTo("2025-05-25"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":2},\"hits\":["
                        + padHit("0000000001-25-000010", "2025-05-25") + ","
                        + padHit("0000000001-25-000001", "2025-05-16") + "]}}")));
        wm.stubFor(get(urlMatching("/Archives/edgar/data/1/.*"))
                .willReturn(aResponse().withHeader("Content-Type", "application/xml").withBody(ONE_TX_FORM4_ON_15TH)));

        // limit=2 makes the pad's collection target 2, i.e. the walk stops once D+1 has answered —
        // the production shape, where a pad day's ~243 hits saturate the deadline's budget at once.
        var s = new EdgarSearchService(wmClient(), wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        s.form4Transactions(LocalDate.parse("2025-05-15"), LocalDate.parse("2025-05-15"), 2);

        // The D+1 filing MUST have been fetched — it is where a trade dated D is reported.
        wm.verify(1, getRequestedFor(urlPathEqualTo("/Archives/edgar/data/1/000000000125000001/f.xml")));
        // The far end of the pad must NOT have been fetched: it cannot carry an in-window trade.
        wm.verify(0, getRequestedFor(urlPathEqualTo("/Archives/edgar/data/1/000000000125000010/f.xml")));
    }

    /** The pad walk is ASCENDING and stops as soon as it holds what the deadline could fetch — for
     *  a day slice that is a single extra EFTS request, not one per pad day. */
    @Test void theNarrowWindowPadWalkIsAscendingAndStopsEarly() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-15"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":1},\"hits\":["
                        + padHit("0000000001-25-000000", "2025-05-15") + "]}}")));
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-16"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":1},\"hits\":["
                        + padHit("0000000001-25-000001", "2025-05-16") + "]}}")));
        wm.stubFor(get(urlMatching("/Archives/edgar/data/1/.*"))
                .willReturn(aResponse().withHeader("Content-Type", "application/xml").withBody(ONE_TX_FORM4_ON_15TH)));

        var s = new EdgarSearchService(wmClient(), wm.baseUrl(), 3600L, System::currentTimeMillis, TICKERS);
        s.form4Transactions(LocalDate.parse("2025-05-15"), LocalDate.parse("2025-05-15"), 2);

        // the pad is asked for day by day, nearest first — never as one D+1..D+10 range
        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-16")).withQueryParam("enddt", equalTo("2025-05-16")));
        wm.verify(0, getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-16")).withQueryParam("enddt", equalTo("2025-05-25")));
        // and it STOPS at D+1: D+2 is never asked for, because the budget is already accounted for
        wm.verify(0, getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-17")));
    }

    /**
     * The wide-window path must not regress: it is backed by the live replay of 2026-08-04
     * (caller window 2026-07-20..2026-07-27, 8 days) and still issues ONE descending pad search
     * over the whole D+1..D+10 range.
     */
    @Test void aWideWindowKeepsTheSingleDescendingPadSearch() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}")));
        svc().form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100);

        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-06-01")).withQueryParam("enddt", equalTo("2025-06-10")));
        // exactly two searches total, as before: the window and the one pad range
        wm.verify(2, getRequestedFor(urlPathEqualTo("/LATEST/search-index")));
    }

    /** The threshold itself: 4 calendar days is still narrow, 5 is already wide. */
    @Test void theNarrowWindowThresholdIsFourCalendarDays() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}")));
        // 2025-05-12..2025-05-15 inclusive = 4 days -> narrow -> per-day pad search
        svc().form4Transactions(LocalDate.parse("2025-05-12"), LocalDate.parse("2025-05-15"), 100);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-16")).withQueryParam("enddt", equalTo("2025-05-16")));

        wm.resetRequests();
        // 2025-05-11..2025-05-15 inclusive = 5 days -> wide -> one ranged pad search
        svc().form4Transactions(LocalDate.parse("2025-05-11"), LocalDate.parse("2025-05-15"), 100);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-16")).withQueryParam("enddt", equalTo("2025-05-25")));
    }

    /**
     * BUG-S1a fix round 2, finding 3 — the EFTS page walk and the archive GETs draw on ONE budget.
     *
     * <p>The pacing used to be a local variable in {@code fetchForm4}'s hit loop, so the 9 %
     * headroom under SEC's 10 req/s was a property of that loop rather than of the process: the
     * page walk that feeds it was unpaced and ran flat out alongside it. The tell is the FIRST
     * archive GET after a search — under one shared budget it must wait out the remainder of the
     * window left by the search request, and under two independent ones it starts immediately.
     *
     * <p>Here the EFTS search costs 40 ms of fake time, so the first archive GET owes 110-40 = 70 ms
     * before it may start. Against the old code that sleep did not exist at all.
     */
    @Test void theEftsPageWalkAndTheArchiveGetsSharePacingBudget() {
        stubForm4Filings(2);
        var clock = new FakeClock();
        var sleeps = new java.util.ArrayList<Long>();
        // Both clients advance the same fake clock, so an EFTS request costs time exactly as an
        // archive request does — which is the point: to SEC they are the same budget.
        org.springframework.http.client.ClientHttpRequestInterceptor costs40ms =
                (request, body, execution) -> { clock.advance(40L); return execution.execute(request, body); };
        var s = new EdgarSearchService(
                de.visterion.agora.data.DataHttp.clientBuilder(15_000, costs40ms).baseUrl(wm.baseUrl()).build(),
                de.visterion.agora.data.DataHttp.clientBuilder(15_000, costs40ms).baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, clock,
                ms -> { sleeps.add(ms); clock.advance(ms); },
                5L * 1024 * 1024, TICKERS);

        s.form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100);

        // search 1 at t=0 (no predecessor) -> ends t=40; search 2 (the pad) owes 70 -> starts 110,
        // ends 150; archive GET 1 owes 70 -> starts 220, ends 260; GET 2 owes 70 -> starts 330.
        // Every gap is the remainder of the window, and the archive stream is spaced from the
        // SEARCH that preceded it rather than starting fresh.
        assertThat(sleeps).containsExactly(70L, 70L, 70L);
    }

    /** Fake millisecond clock, advanced explicitly — never by wall time. Single-threaded by use. */
    private static final class FakeClock implements java.util.function.LongSupplier {
        private long t;
        @Override public long getAsLong() { return t; }
        void advance(long ms) { t += ms; }
    }

    /**
     * A service whose every archive GET consumes exactly {@code fetchMs} of fake time and whose
     * throttle sleeps advance the same fake clock, so the whole schedule is deterministic.
     *
     * <p>Both clients go through {@link de.visterion.agora.data.DataHttp} — the factory production
     * pins — for the reason spelled out on {@link #wmClient()}.
     */
    private EdgarSearchService pacedSvc(FakeClock clock, long fetchMs, java.util.List<Long> sleeps) {
        org.springframework.http.client.ClientHttpRequestInterceptor costsFakeTime =
                (request, body, execution) -> {
                    clock.advance(fetchMs);
                    return execution.execute(request, body);
                };
        return new EdgarSearchService(
                wmClient(),
                de.visterion.agora.data.DataHttp.clientBuilder(15_000, costsFakeTime).baseUrl(wm.baseUrl()).build(),
                wm.baseUrl(), 3600L, clock,
                ms -> { sleeps.add(ms); clock.advance(ms); },
                5L * 1024 * 1024, TICKERS);
    }

    /** One non-derivative transaction, dated inside every window these tests use. */
    private static final String ONE_TX_FORM4 = """
            <ownershipDocument>
              <issuer><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
              <reportingOwner><reportingOwnerId><rptOwnerName>X</rptOwnerName></reportingOwnerId></reportingOwner>
              <nonDerivativeTable><nonDerivativeTransaction>
                <transactionDate><value>2025-05-01</value></transactionDate>
                <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                <transactionAmounts><transactionShares><value>1</value></transactionShares>
                  <transactionPricePerShare><value>1</value></transactionPricePerShare></transactionAmounts>
              </nonDerivativeTransaction></nonDerivativeTable>
            </ownershipDocument>
            """;

    /**
     * Stubs the caller-window search with {@code n} Form-4 hits (each resolving to its own archive
     * path under /Archives/edgar/data/1/), an empty late-filing pad, and one archive document
     * serving every one of those paths.
     */
    private static void stubForm4Filings(int n) {
        StringBuilder sb = new StringBuilder("{\"hits\":{\"total\":{\"value\":").append(n).append("},\"hits\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"_id\":\"0000000001-25-").append(String.format("%06d", i))
              .append(":f.xml\",\"_source\":{\"ciks\":[\"1\"],\"display_names\":[\"Filer ").append(i)
              .append("\"],\"file_date\":\"2025-05-01\",\"file_type\":\"4\"}}");
        }
        sb.append("]}}");
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-05-01")).willReturn(okJson(sb.toString())));
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .withQueryParam("startdt", equalTo("2025-06-01"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}")));
        wm.stubFor(get(urlMatching("/Archives/edgar/data/1/.*"))
                .willReturn(aResponse().withHeader("Content-Type", "application/xml").withBody(ONE_TX_FORM4)));
    }

    /**
     * A fetch that consumes most of the throttle window must sleep only the REMAINDER, so two
     * consecutive archive GETs start exactly THROTTLE_MS apart.
     *
     * <p>Schedule with an 80ms fetch: GET 1 starts at t=0 and ends at 80; the loop then owes only
     * 30ms before GET 2 may start at t=110; the same again for GET 3 at t=220. So the sleeps are
     * 30, 30 — and the STARTS are 110 apart, which is the number SEC's rate limit is about.
     */
    @Test void throttleSleepsOnlyTheRemainderOfTheWindowAfterASlowFetch() {
        stubForm4Filings(3);
        var clock = new FakeClock();
        var sleeps = new java.util.ArrayList<Long>();
        var r = pacedSvc(clock, 80L, sleeps)
                .form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100);

        assertThat(r.transactions()).hasSize(3);
        // The two leading 110s are the EFTS searches, which cost no fake time here and therefore
        // owe the whole window; the 30s are the archive GETs, each owing only the remainder after
        // its 80ms fetch. Both halves draw on one budget — see
        // theEftsPageWalkAndTheArchiveGetsSharePacingBudget.
        assertThat(sleeps).containsExactly(110L, 110L, 30L, 30L);
        // archive GETs start at 220, 330, 440 — i.e. 110 apart — and the last ends at 440 + 80.
        assertThat(clock.getAsLong()).isEqualTo(520L);
    }

    /** A fetch that already overran the window owes nothing — it must not sleep at all. */
    @Test void aFetchThatOverrunsTheThrottleWindowSleepsZero() {
        stubForm4Filings(3);
        var clock = new FakeClock();
        var sleeps = new java.util.ArrayList<Long>();
        var r = pacedSvc(clock, 150L, sleeps)
                .form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 100);

        assertThat(r.transactions()).hasSize(3);
        // Two waits only, and neither follows an overrunning fetch: the pad search owes the whole
        // window after the zero-cost window search, and archive GET 1 owes it after the zero-cost
        // pad search. GETs 2 and 3 each follow a 150ms fetch that has already overrun the 110ms
        // window, so they owe NOTHING and are paced by their own duration instead.
        assertThat(sleeps).containsExactly(110L, 110L);
        assertThat(clock.getAsLong()).isEqualTo(670L);   // 110 + 110 waits + 3 x 150 fetch
    }

    /**
     * What the throttle costs the caller: with the spacing fixed, the aggregate deadline admits
     * one filing per THROTTLE_MS rather than one per (THROTTLE_MS + fetch).
     *
     * <p>Arithmetic, 80ms fetch against the 30s FORM4_DEADLINE_MS. Fetch n starts at n x 110ms,
     * so the loop stops at n = 273 — 30 000 / 110 filings, the fetch cost having disappeared from
     * the per-filing price. With the flat delay the price was 190ms and the same deadline bought
     * 159. That 1.7x is the whole point of BUG-S1a: the insider hunter's cluster threshold
     * (3 filers on one ticker) needs coverage of the window, and 159 of ~1,700 filings is 9%.
     */
    @Test void theDeadlineNowAdmitsOneFilingPerThrottleWindowNotPerThrottlePlusFetch() {
        stubForm4Filings(400);
        var clock = new FakeClock();
        var sleeps = new java.util.ArrayList<Long>();
        var r = pacedSvc(clock, 80L, sleeps)
                .form4Transactions(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-31"), 1000);

        // one transaction per filing, so the row count IS the filings-read count.
        // 272 = MAX_FILINGS_PER_DEADLINE (30_000 / 110). The flat delay read 159. It is 272 rather
        // than 273 because the first archive GET is now spaced from the EFTS search that preceded
        // it — one shared budget, so the searches are part of the same 110ms cadence.
        assertThat(r.transactions()).hasSize(272);
        assertThat(r.truncated()).isTrue();             // 400 hits offered, 272 read
        // The first two waits are the EFTS searches (zero-cost here, so they owe the whole window)
        // plus archive GET 1 spaced off the last of them. Every gap from then on is the REMAINDER
        // of the window after an 80ms fetch, never the whole window.
        assertThat(sleeps).hasSizeGreaterThan(3);
        assertThat(sleeps.subList(3, sleeps.size())).allMatch(ms -> ms == 30L);
    }
}
