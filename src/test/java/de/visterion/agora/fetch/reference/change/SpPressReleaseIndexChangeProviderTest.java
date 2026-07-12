package de.visterion.agora.fetch.reference.change;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class SpPressReleaseIndexChangeProviderTest {

    static WireMockServer wm;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private static final List<String> ADD = List.of("(?i)^(.+?)\\s+Set to Join S&P 500");
    private static final List<String> REMOVE =
            List.of("(?i)^(.+?)\\s+(?:Set to be Removed|to be Removed) from S&P 500");

    private SpPressReleaseIndexChangeProvider provider() {
        return new SpPressReleaseIndexChangeProvider(
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                "/index.php?s=2429&l=100&pagetemplate=rss",
                ADD, REMOVE, 3600L, 604800L, System::currentTimeMillis);
    }

    private static String item(String title, String path, String pubDate) {
        return "<item><title>" + title + "</title>"
                + "<link>" + wm.baseUrl() + path + "</link>"
                + "<pubDate>" + pubDate + "</pubDate>"
                + "<guid>" + wm.baseUrl() + path + "</guid></item>";
    }

    private static void stubFeed(String... items) {
        String rss = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<rss version=\"2.0\"><channel><title>News Release Archive</title>"
                + "<link>https://press.spglobal.com/</link>"
                + String.join("", items) + "</channel></rss>";
        wm.stubFor(get(urlPathEqualTo("/index.php")).willReturn(aResponse()
                .withHeader("Content-Type", "application/xml").withBody(rss)));
    }

    private static void stubBody(String path, String body) {
        wm.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse()
                .withHeader("Content-Type", "text/html").withBody(body)));
    }

    @Test void parsesCleanAddRemoveAndDropsMalformed() {
        stubFeed(
                // clean add ("will replace" documents both sides)
                item("Solaris Energy Infrastructure Set to Join S&amp;P 500", "/release-add",
                        "Thu, 09 Jul 2026 17:47:00 -0400"),
                // clean remove (single deletion, explicit year)
                item("Acme Corp Set to be Removed from S&amp;P 500", "/release-remove",
                        "Mon, 06 Jul 2026 07:48:00 -0400"),
                // title matches but body yields no ticker/effective date -> DROP
                item("Foo Holdings Set to Join S&amp;P 500", "/release-bad",
                        "Tue, 01 Jul 2026 09:00:00 -0400"),
                // not an index add/remove -> never fetched
                item("S&amp;P Global Schedules Second Quarter 2026 Earnings", "/release-earnings",
                        "Mon, 06 Jul 2026 07:48:00 -0400"));

        stubBody("/release-add",
                "NEW YORK, July 9, 2026 -- Solaris Energy Infrastructure Inc. (NYSE: SEI) will replace "
                        + "Catalyst Pharmaceuticals Inc. (NASD: CPRX) in the S&P 500 effective prior to "
                        + "the opening of trading on Wednesday, July 15.");
        stubBody("/release-remove",
                "NEW YORK -- Acme Corp (NYSE: ACME) will be removed from the S&P 500 effective prior to "
                        + "the opening of trading on Monday, August 3, 2026.");
        stubBody("/release-bad", "This release contains no structured constituent-change information at all.");

        List<IndexChange> out = provider().changes("sp500");

        assertThat(out).extracting(IndexChange::symbol, IndexChange::action, IndexChange::effectiveDate)
                .containsExactlyInAnyOrder(
                        tuple("SEI", "add", LocalDate.of(2026, 7, 15)),
                        tuple("CPRX", "remove", LocalDate.of(2026, 7, 15)),
                        tuple("ACME", "remove", LocalDate.of(2026, 8, 3)));
        assertThat(out).allSatisfy(c -> {
            assertThat(c.index()).isEqualTo("sp500");
            assertThat(c.source()).isEqualTo("sp_press");
            assertThat(c.announcementDate()).isNotNull();
        });
        // the malformed "Foo" item was dropped, never half-emitted
        assertThat(out).extracting(IndexChange::symbol).doesNotContain("FOO");
    }

    @Test void parsesReleaseWithHtmlEntitiesTagsAndNewlines() {
        // Real S&P release pages carry &nbsp; between the exchange label and the ticker, inline
        // tags inside the target sentence, and line breaks mid-sentence. Normalisation to plain
        // text must let the regexes still extract add + remove + effective date.
        stubFeed(item("Globex Corporation Set to Join S&amp;P 500", "/release-html",
                "Thu, 09 Jul 2026 17:47:00 -0400"));
        stubBody("/release-html",
                "<p>NEW YORK, July 9, 2026 -- <b>Globex Corporation</b> (NYSE:&nbsp;GBX) will replace\n"
                        + "Initech Inc. (NASD:&nbsp;INI) in the <span>S&amp;P&nbsp;500</span> effective prior to the\n"
                        + "opening of trading on <strong>Wednesday, July&nbsp;15</strong>.</p>");

        List<IndexChange> out = provider().changes("sp500");
        assertThat(out).extracting(IndexChange::symbol, IndexChange::action, IndexChange::effectiveDate)
                .containsExactlyInAnyOrder(
                        tuple("GBX", "add", LocalDate.of(2026, 7, 15)),
                        tuple("INI", "remove", LocalDate.of(2026, 7, 15)));
    }

    @Test void unknownIndexReturnsEmptyWithoutFetching() {
        assertThat(provider().changes("nasdaq100")).isEmpty();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/index.php")));
    }

    @Test void feedFailureDegradesToEmpty() {
        wm.stubFor(get(urlPathEqualTo("/index.php")).willReturn(aResponse().withStatus(500)));
        assertThat(provider().changes("sp500")).isEmpty();
    }

    @Test void bodyFailureDropsItemButKeepsOthers() {
        stubFeed(
                item("Good Corp Set to Join S&amp;P 500", "/good", "Thu, 09 Jul 2026 17:47:00 -0400"),
                item("Bad Corp Set to Join S&amp;P 500", "/bad", "Thu, 09 Jul 2026 17:47:00 -0400"));
        stubBody("/good",
                "Good Corp (NYSE: GOOD) will replace Old Corp (Nasdaq: OLD) in the S&P 500 effective "
                        + "prior to the opening of trading on Friday, July 17.");
        wm.stubFor(get(urlPathEqualTo("/bad")).willReturn(aResponse().withStatus(500)));

        List<IndexChange> out = provider().changes("sp500");
        assertThat(out).extracting(IndexChange::symbol)
                .containsExactlyInAnyOrder("GOOD", "OLD");
    }

    // --- title regex unit tests ---

    @Test void titleRegexClassifiesAddRemoveAndIgnoresOthers() {
        var p = provider();
        assertThat(p.classify("Solaris Energy Infrastructure Set to Join S&P 500")).isEqualTo("add");
        assertThat(p.classify("Acme Corp Set to be Removed from S&P 500")).isEqualTo("remove");
        assertThat(p.classify("Acme Corp to be Removed from S&P 500")).isEqualTo("remove");
        // SmallCap 600 / MidCap 400 releases are not S&P 500 -> ignored
        assertThat(p.classify("Midera Food Processing Set to Join S&P SmallCap 600")).isNull();
        assertThat(p.classify("S&P Global Schedules Second Quarter Earnings")).isNull();
    }

    @Test void deriveIndexReadsIndexLabelFromTitle() {
        assertThat(SpPressReleaseIndexChangeProvider.deriveIndex("Foo Set to Join S&P 500")).isEqualTo("sp500");
        assertThat(SpPressReleaseIndexChangeProvider.deriveIndex("Foo Set to Join S&P MidCap 400")).isEqualTo("sp400");
        assertThat(SpPressReleaseIndexChangeProvider.deriveIndex("Foo Set to Join S&P SmallCap 600")).isEqualTo("sp600");
        assertThat(SpPressReleaseIndexChangeProvider.deriveIndex("S&P Global Schedules Earnings")).isNull();
    }

    // --- HTML normalisation unit tests ---

    @Test void normalizeHtmlDecodesEntitiesStripsTagsCollapsesWhitespace() {
        String out = SpPressReleaseIndexChangeProvider.normalizeHtml(
                "<p>Acme&nbsp;Corp\n(NYSE:&nbsp;ACME) &amp; friends&#8217;\n\n  quarter &#x2014; done</p>");
        assertThat(out).isEqualTo("Acme Corp (NYSE: ACME) & friends’ quarter — done");
    }

    // --- year-inference unit tests ---

    @Test void effectiveDateUsesExplicitYearWhenPresent() {
        LocalDate d = SpPressReleaseIndexChangeProvider.parseEffectiveDate(
                "effective prior to the opening of trading on Thursday, July 15, 2027.",
                LocalDate.of(2026, 1, 1));
        assertThat(d).isEqualTo(LocalDate.of(2027, 7, 15));
    }

    @Test void effectiveDateInfersYearFromAnnouncementSameYear() {
        LocalDate d = SpPressReleaseIndexChangeProvider.parseEffectiveDate(
                "effective prior to the opening of trading on Wednesday, July 15.",
                LocalDate.of(2026, 7, 9));
        assertThat(d).isEqualTo(LocalDate.of(2026, 7, 15));
    }

    @Test void effectiveDateRollsOverDecemberToJanuary() {
        LocalDate d = SpPressReleaseIndexChangeProvider.parseEffectiveDate(
                "effective prior to the opening of trading on Friday, January 2.",
                LocalDate.of(2025, 12, 29));
        assertThat(d).isEqualTo(LocalDate.of(2026, 1, 2));
    }

    @Test void effectiveDateNullWhenAbsent() {
        assertThat(SpPressReleaseIndexChangeProvider.parseEffectiveDate(
                "no effective clause here", LocalDate.of(2026, 7, 9))).isNull();
    }
}
