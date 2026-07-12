package de.visterion.agora.fetch.reference.change;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class RussellReconstitutionIndexChangeProviderTest {

    static WireMockServer wm;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    // 2025 reconstitution: preliminary 2025-05-23, effective 2025-06-27 (a real observed pair).
    private static final RussellSchedule S2025 = new RussellSchedule(2025,
            LocalDate.of(2025, 4, 30), LocalDate.of(2025, 5, 23), LocalDate.of(2025, 6, 27));
    private static final RussellReconstitutionCalendar CAL =
            new RussellReconstitutionCalendar(Map.of(2025, S2025));

    // The stubbed PDF "bytes" are just the fixture text; the injected extractor decodes them.
    private static final PdfTextExtractor TEXT_EXTRACTOR = bytes -> new String(bytes, StandardCharsets.UTF_8);

    private static final String ADDITIONS = """
            Russell 3000® Index - Additions
            Company Symbol Industry
            AARDVARK THERAPEUTICS AARD Health Care
            BROOKFIELD ASSET MANAGEM BAM Financials
            CHEWY CHWY Consumer Discretionary
            For more information about our indexes, please visit lseg.com/ftse-russell.
            """;
    private static final String DELETIONS = """
            Russell 3000® Index - Deletions
            Company Symbol Industry
            OLDCO INDUSTRIES OLDC Industrials
            """;
    private static final String IWB_CSV = """
            "iShares Russell 1000 ETF"
            "Fund Holdings as of","Jun 27, 2025"

            "Ticker","Name","Sector","Asset Class","Weight (%)"
            "BAM","Brookfield Asset Management","Financials","Equity","0.02"
            "AAPL","Apple Inc","Technology","Equity","5.00"

            "The content contained herein is owned by BlackRock."
            """;
    private static final String IWM_CSV = """
            "iShares Russell 2000 ETF"
            "Fund Holdings as of","Jun 27, 2025"

            "Ticker","Name","Sector","Asset Class","Weight (%)"
            "AARD","Aardvark Therapeutics","Health Care","Equity","0.05"
            "CHWY","Chewy Inc","Consumer Discretionary","Equity","0.10"
            "-","CASH COLLATERAL","Cash and/or Derivatives","Cash","0.01"

            "The content contained herein is owned by BlackRock."
            """;

    // today inside the 2025 window (preliminary .. effective + 14d tail).
    private RussellReconstitutionIndexChangeProvider provider(LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        return new RussellReconstitutionIndexChangeProvider(
                RestClient.builder().baseUrl(wm.baseUrl()).build(),   // LSEG
                RestClient.builder().baseUrl(wm.baseUrl()).build(),   // iShares
                TEXT_EXTRACTOR, CAL,
                "/{list}-{date}.pdf", "/iwb.csv", "/iwm.csv",
                14, 604800L, 86400L, clock, System::currentTimeMillis);
    }

    private static void stubPdf(String path, String body) {
        wm.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse()
                .withHeader("Content-Type", "application/pdf")
                .withBody(body.getBytes(StandardCharsets.UTF_8))));
    }

    private static void stubCsv(String path, String body) {
        wm.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse()
                .withHeader("Content-Type", "text/csv").withBody(body)));
    }

    // The final effective-dated PDFs (20250627); preliminary-dated (20250523) is the fallback.
    private static void stubEffectivePdfs() {
        stubPdf("/ru3000-additions-20250627.pdf", ADDITIONS);
        stubPdf("/ru3000-deletions-20250627.pdf", DELETIONS);
    }

    @Test void resolvesBucketsViaIsharesAndEmitsWindowChanges() {
        stubEffectivePdfs();
        stubCsv("/iwb.csv", IWB_CSV);
        stubCsv("/iwm.csv", IWM_CSV);
        var p = provider(LocalDate.of(2025, 6, 30));

        // russell1000: only BAM (present in IWB holdings).
        assertThat(p.changes("russell1000"))
                .extracting(IndexChange::symbol, IndexChange::action, IndexChange::index,
                        IndexChange::announcementDate, IndexChange::effectiveDate, IndexChange::source)
                .containsExactly(tuple("BAM", "add", "russell1000",
                        LocalDate.of(2025, 5, 23), LocalDate.of(2025, 6, 27), "russell_reconstitution"));

        // russell2000: AARD + CHWY (in IWM) and OLDC (deletion -> default russell2000).
        assertThat(p.changes("russell2000"))
                .extracting(IndexChange::symbol, IndexChange::action, IndexChange::index)
                .containsExactlyInAnyOrder(
                        tuple("AARD", "add", "russell2000"),
                        tuple("CHWY", "add", "russell2000"),
                        tuple("OLDC", "remove", "russell2000"));
    }

    @Test void isharesWalledHtmlDegradesToDefaultRussell2000() {
        stubEffectivePdfs();
        // The real bot wall answers with an HTML product page under a lying text/csv content type.
        String html = "<!DOCTYPE html>\n<html><head><title>iShares</title></head><body>walled</body></html>";
        wm.stubFor(get(urlPathEqualTo("/iwb.csv")).willReturn(aResponse()
                .withHeader("Content-Type", "text/csv").withBody(html)));
        wm.stubFor(get(urlPathEqualTo("/iwm.csv")).willReturn(aResponse()
                .withHeader("Content-Type", "text/csv").withBody(html)));
        var p = provider(LocalDate.of(2025, 6, 30));

        // Unresolvable buckets default to russell2000: R1000 empties, R2000 carries everything.
        assertThat(p.changes("russell1000")).isEmpty();
        assertThat(p.changes("russell2000")).extracting(IndexChange::symbol)
                .containsExactlyInAnyOrder("AARD", "BAM", "CHWY", "OLDC");
    }

    @Test void isharesUnreachableDegradesToDefaultRussell2000() {
        stubEffectivePdfs();
        wm.stubFor(get(urlPathEqualTo("/iwb.csv")).willReturn(aResponse().withStatus(503)));
        wm.stubFor(get(urlPathEqualTo("/iwm.csv")).willReturn(aResponse().withStatus(503)));
        var p = provider(LocalDate.of(2025, 6, 30));

        assertThat(p.changes("russell1000")).isEmpty();
        assertThat(p.changes("russell2000")).extracting(IndexChange::symbol)
                .containsExactlyInAnyOrder("AARD", "BAM", "CHWY", "OLDC");
    }

    @Test void postEffectiveFallsBackToPreliminaryDatedPdfWhenFinalAbsent() {
        // On/after the effective date the final (effective-dated) PDF is tried first; here it 404s,
        // so the provider falls back to the preliminary-dated list.
        wm.stubFor(get(urlPathMatching("/ru3000-.*-20250627\\.pdf")).willReturn(aResponse().withStatus(404)));
        stubPdf("/ru3000-additions-20250523.pdf", ADDITIONS);
        stubPdf("/ru3000-deletions-20250523.pdf", DELETIONS);
        stubCsv("/iwb.csv", IWB_CSV);
        stubCsv("/iwm.csv", IWM_CSV);
        var p = provider(LocalDate.of(2025, 6, 30)); // past the effective date

        assertThat(p.changes("russell2000")).extracting(IndexChange::symbol)
                .containsExactlyInAnyOrder("AARD", "CHWY", "OLDC");
        // the final-dated PDF was attempted first (then fell back)
        wm.verify(getRequestedFor(urlPathEqualTo("/ru3000-additions-20250627.pdf")));
    }

    @Test void preEffectiveWindowSkipsFinalDatedPdf() {
        // Before the effective date the final list does not exist yet; the provider must go
        // straight to the preliminary-dated PDF and never request the effective-dated one (no 404 spam).
        stubPdf("/ru3000-additions-20250523.pdf", ADDITIONS);
        stubPdf("/ru3000-deletions-20250523.pdf", DELETIONS);
        stubCsv("/iwb.csv", IWB_CSV);
        stubCsv("/iwm.csv", IWM_CSV);
        var p = provider(LocalDate.of(2025, 6, 1)); // inside window, before effective

        assertThat(p.changes("russell2000")).extracting(IndexChange::symbol)
                .containsExactlyInAnyOrder("AARD", "CHWY", "OLDC");
        wm.verify(0, getRequestedFor(urlPathMatching("/ru3000-.*-20250627\\.pdf")));
    }

    @Test void missingPdfsDegradeToEmpty() {
        wm.stubFor(get(urlPathMatching("/ru3000-.*\\.pdf")).willReturn(aResponse().withStatus(404)));
        var p = provider(LocalDate.of(2025, 6, 30));
        assertThat(p.changes("russell2000")).isEmpty();
        assertThat(p.changes("russell1000")).isEmpty();
    }

    @Test void outsideReconstitutionWindowReturnsEmptyWithoutFetching() {
        var p = provider(LocalDate.of(2025, 1, 15)); // well before the preliminary date
        assertThat(p.changes("russell2000")).isEmpty();
        wm.verify(0, getRequestedFor(urlPathMatching("/ru3000-.*")));
    }

    @Test void afterWindowTailReturnsEmpty() {
        // effective 2025-06-27 + 14d tail = 2025-07-11; 2025-07-20 is past it.
        var p = provider(LocalDate.of(2025, 7, 20));
        assertThat(p.changes("russell2000")).isEmpty();
    }

    @Test void ignoresUnsupportedIndex() {
        stubEffectivePdfs();
        var p = provider(LocalDate.of(2025, 6, 30));
        assertThat(p.changes("sp500")).isEmpty();
        assertThat(p.changes("nasdaq100")).isEmpty();
        wm.verify(0, getRequestedFor(urlPathMatching("/ru3000-.*")));
    }

    @Test void orderIsAfterSpProvider() {
        assertThat(provider(LocalDate.of(2025, 6, 30)).order()).isEqualTo(20);
    }

    // --- iShares CSV parsing unit tests ---

    @Test void parseIsharesTickersReadsTickerColumnSkipsCashAndFooter() {
        Set<String> tickers = RussellReconstitutionIndexChangeProvider.parseIsharesTickers(IWM_CSV);
        assertThat(tickers).containsExactly("AARD", "CHWY"); // "-" cash row and footer excluded
    }

    @Test void parseIsharesTickersAcceptsDotSeparatedClassShareTicker() {
        String csv = """
                "iShares Russell 1000 ETF"

                "Ticker","Name","Sector","Asset Class"
                "BRK.B","Berkshire Hathaway","Financials","Equity"
                """;
        assertThat(RussellReconstitutionIndexChangeProvider.parseIsharesTickers(csv))
                .containsExactly("BRK.B");
    }

    @Test void parseIsharesTickersReturnsEmptyForHtmlBody() {
        assertThat(RussellReconstitutionIndexChangeProvider.parseIsharesTickers(
                "<!DOCTYPE html><html>bot wall</html>")).isEmpty();
        assertThat(RussellReconstitutionIndexChangeProvider.parseIsharesTickers(null)).isEmpty();
        assertThat(RussellReconstitutionIndexChangeProvider.parseIsharesTickers("")).isEmpty();
    }
}
