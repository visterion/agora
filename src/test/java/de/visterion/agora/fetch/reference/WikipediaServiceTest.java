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
        String wikitext = "{| class=\"wikitable sortable\" id=\"constituents\"\n! Symbol !! Security !! GICS Sector !! Date added\n" +
                "|-\n| [[Apple Inc.|AAPL]] || Apple Inc. || Information Technology || 1982-11-30\n" +
                "|-\n| MSFT || Microsoft || Information Technology || 1994-06-01\n|}";
        wm.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(okJson("{\"parse\":{\"wikitext\":" + toJsonString(wikitext) + "}}")));
        List<Constituent> c = svc().constituents("sp500");
        assertThat(c).hasSize(2);
        assertThat(c.get(0).symbol()).isEqualTo("AAPL");
        assertThat(c.get(0).sector()).isEqualTo("Information Technology");
    }

    @Test void parsesRealisticConstituentsTable() {
        // Mirrors the real "List of S&P 500 companies" wikitext: a leading <!--EDITORS...-->
        // comment that itself contains the phrase "Date added", the real table anchored by
        // id="constituents", a header where Symbol and GICS Sector carry inline wikilinks, and
        // data rows whose symbol cells are {{NyseSymbol|..}} / {{NasdaqSymbol|..}} templates.
        String wikitext = "<!--EDITORS PLEASE TAKE NOTE:\n" +
                " 3. Most of the dates in the \"Date added\" column agree with the source.\n" +
                "-->\n" +
                "Some lead paragraph text.\n" +
                "{| class=\"wikitable sortable mw-collapsible sticky-header\" id=\"constituents\"\n" +
                "|-\n" +
                "![[Ticker symbol|Symbol]]\n" +
                "! Security !! [[Global Industry Classification Standard|GICS]] Sector !! GICS Sub-Industry !! Headquarters Location !! Date added !! [[Central Index Key|CIK]] !! Founded\n" +
                "|-\n" +
                "|{{NyseSymbol|MMM}}\n" +
                "|[[3M]]|| Industrials || Industrial Conglomerates || [[Saint Paul, Minnesota]] || 1957-03-04 || 0000066740 || 1902\n" +
                "|-\n" +
                "|{{NasdaqSymbol|AOS}}\n" +
                "|[[A. O. Smith]]|| Industrials || Building Products || [[Milwaukee, Wisconsin]] || 2017-07-26 || 0000091142 || 1916\n" +
                "|}";
        wm.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(okJson("{\"parse\":{\"wikitext\":" + toJsonString(wikitext) + "}}")));
        List<Constituent> c = svc().constituents("sp500");
        assertThat(c).hasSize(2);
        assertThat(c.get(0).symbol()).isEqualTo("MMM");
        assertThat(c.get(0).name()).isEqualTo("3M");
        assertThat(c.get(0).sector()).isEqualTo("Industrials");
        assertThat(c.get(1).symbol()).isEqualTo("AOS");
        assertThat(c.get(1).name()).isEqualTo("A. O. Smith");
        assertThat(c.get(1).sector()).isEqualTo("Industrials");
    }

    @Test void stripsEditorCommentFromSymbolCell() {
        // Real editor comment from the "List of S&P 500 companies" page: it sits right after the
        // wikilink in the Symbol cell of the BRK.B row and, if not stripped, its fragments leak
        // into the parsed symbol and poison every downstream provider call.
        String wikitext = "{| class=\"wikitable sortable\" id=\"constituents\"\n! Symbol !! Security !! GICS Sector !! Date added\n" +
                "|-\n| [[Berkshire Hathaway|BRK.B]] <!-- DO NOT CHANGE THIS TICKER TO BRK-B. IT IS NOT CORRECT AND WILL BE REVERTED. YOU'VE BEEN WARNED! --> || Berkshire Hathaway || Financials || 2010-02-16\n|}";
        wm.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(okJson("{\"parse\":{\"wikitext\":" + toJsonString(wikitext) + "}}")));
        List<Constituent> c = svc().constituents("sp500");
        assertThat(c).hasSize(1);
        assertThat(c.get(0).symbol()).isEqualTo("BRK.B");
    }

    @Test void stripsMultiLineHtmlCommentFromCell() {
        // A comment can also span multiple wikitext lines; the DOTALL match must still remove it
        // as one block rather than leaving fragments of the opening/closing markers behind. The
        // row/cell tokenizer above is strictly line-based (any physical line inside a cell that
        // doesn't start with the cell marker is dropped, so it can never hand a raw multi-line
        // cell value through the full fetch/parse path) — so this exercises stripWiki directly.
        String cell = "[[Berkshire Hathaway|BRK.B]] <!--\n  DO NOT CHANGE THIS TICKER\n  MULTIPLE LINES\n-->";
        assertThat(WikipediaService.stripWiki(cell)).isEqualTo("BRK.B");
    }

    @Test void skipsRowWhoseSymbolStillContainsWhitespaceAfterStripping() {
        // A markup variant stripWiki doesn't handle must degrade to "one missing constituent",
        // never to a poisoned multi-word symbol travelling downstream.
        String wikitext = "{| class=\"wikitable sortable\" id=\"constituents\"\n! Symbol !! Security !! GICS Sector !! Date added\n" +
                "|-\n| FOO BAR || Foo Bar Inc. || Industrials || 2020-01-01\n" +
                "|-\n| MSFT || Microsoft || Information Technology || 1994-06-01\n|}";
        wm.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(okJson("{\"parse\":{\"wikitext\":" + toJsonString(wikitext) + "}}")));
        List<Constituent> c = svc().constituents("sp500");
        assertThat(c).hasSize(1);
        assertThat(c.get(0).symbol()).isEqualTo("MSFT");
    }

    @Test void unknownIndexThrowsUnavailable() {
        assertThatThrownBy(() -> svc().constituents("nasdaq100")).isInstanceOf(MarketDataException.class);
    }

    @Test void fetchFailureThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/w/api.php")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> svc().constituents("sp500")).isInstanceOf(MarketDataException.class);
    }

    @Test void emptyParseResultThrowsInsteadOfCachingEmptyList() {
        // Wikitext without the "id=\"constituents\"" anchor: the table can't be located, so
        // parse() would previously return an empty list. That must not become a cached
        // "0 constituents" answer — it must throw so nothing is cached.
        String wikitext = "Some unrelated page content with no constituents table at all.";
        wm.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(okJson("{\"parse\":{\"wikitext\":" + toJsonString(wikitext) + "}}")));
        assertThatThrownBy(() -> svc().constituents("sp500")).isInstanceOf(MarketDataException.class);
    }

    @Test void serverErrorThrowsAndNothingCached() {
        wm.stubFor(get(urlPathEqualTo("/w/api.php")).willReturn(aResponse().withStatus(500)));
        WikipediaService svc = svc();
        assertThatThrownBy(() -> svc.constituents("sp500")).isInstanceOf(MarketDataException.class);

        // Reconfigure to succeed; a subsequent call must re-hit upstream (nothing was cached on failure).
        String wikitext = "{| class=\"wikitable sortable\" id=\"constituents\"\n! Symbol !! Security !! GICS Sector !! Date added\n" +
                "|-\n| [[Apple Inc.|AAPL]] || Apple Inc. || Information Technology || 1982-11-30\n|}";
        wm.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(okJson("{\"parse\":{\"wikitext\":" + toJsonString(wikitext) + "}}")));
        List<Constituent> c = svc.constituents("sp500");
        assertThat(c).hasSize(1);
        assertThat(c.get(0).symbol()).isEqualTo("AAPL");
    }

    @Test
    void slowResponseFailsFastAsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(okJson("{}").withFixedDelay(3_000)));
        var fast = new WikipediaService(wm.baseUrl(), "TestAgent/1.0", "List of S&P 500 companies", 86_400L, 250L);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> fast.constituents("sp500"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
        assertThat((System.nanoTime() - t0) / 1_000_000L).isLessThan(2_500L);
    }

    // Helper: JSON-encode a string (quotes + escapes) for embedding in the stub body.
    private static String toJsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
