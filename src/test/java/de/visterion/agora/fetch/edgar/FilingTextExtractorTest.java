package de.visterion.agora.fetch.edgar;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FilingTextExtractorTest {

    @Test void slicesFromSummaryTermSheetHeading() {
        String html = "<html><body><p>cover page boilerplate</p>"
                + "<p>SUMMARY TERM SHEET</p>"
                + "<p>The offer is $52.00 in cash per share.</p></body></html>";
        var ex = FilingTextExtractor.extract(html);
        assertThat(ex.sectionFound()).isTrue();
        assertThat(ex.text()).startsWith("SUMMARY TERM SHEET");
        assertThat(ex.text()).contains("$52.00 in cash");
        assertThat(ex.text()).doesNotContain("cover page boilerplate");
        assertThat(ex.truncated()).isFalse();
    }

    @Test void fallsBackToLeadingWindowWhenNoHeading() {
        String html = "<html><body><p>Registration statement body with no summary heading.</p></body></html>";
        var ex = FilingTextExtractor.extract(html);
        assertThat(ex.sectionFound()).isFalse();
        assertThat(ex.text()).contains("Registration statement body");
    }

    @Test void stripsTagsScriptAndEntities() {
        String html = "<html><head><style>.x{color:red}</style></head>"
                + "<body><script>var x=1;</script><p>Deal&nbsp;terms &amp; conditions</p></body></html>";
        var ex = FilingTextExtractor.extract(html);
        assertThat(ex.text()).doesNotContain("<").doesNotContain("color:red").doesNotContain("var x=1");
        assertThat(ex.text()).contains("Deal terms & conditions");
    }

    @Test void truncatesPastBudget() {
        StringBuilder sb = new StringBuilder("<p>SUMMARY TERM SHEET</p><p>");
        sb.append("a".repeat(FilingTextExtractor.MAX_CHARS + 5_000));
        sb.append("</p>");
        var ex = FilingTextExtractor.extract(sb.toString());
        assertThat(ex.truncated()).isTrue();
        assertThat(ex.text().length()).isEqualTo(FilingTextExtractor.MAX_CHARS);
    }

    // Low: numeric entity decoding via a small map + &#NN;/&#xHH; — not just the previous
    // hardcoded 8-entity list.
    @Test void decodesNumericAndHexEntities() {
        String html = "<p>caf&#233; &#x2019;tis &#8220;quoted&#8221;</p>";
        var ex = FilingTextExtractor.extract(html);
        assertThat(ex.text()).contains("café").contains("’tis").contains("“quoted”");
    }

    // Information statements (10-12B, EX-99.1) carry the terms as plain prose before ANY
    // heading — the heading-seeking SECTION mode can land hundreds of thousands of characters
    // past the answer. LEADING mode ignores headings entirely and starts at character 0.
    @Test void leadingModeIgnoresHeadingAndStartsAtZero() {
        String html = "<p>"
                + "a".repeat(80)
                + " one share for every two shares "
                + "b".repeat(80)
                + "</p><p>Questions and Answers</p><p>"
                + "c".repeat(80)
                + "</p>";
        var leading = FilingTextExtractor.extract(html, FilingTextExtractor.Mode.LEADING);
        assertThat(leading.text()).startsWith("a".repeat(80));
        assertThat(leading.text()).contains("one share for every two shares");
        assertThat(leading.sectionFound()).isFalse();

        var section = FilingTextExtractor.extract(html, FilingTextExtractor.Mode.SECTION);
        assertThat(section.text()).startsWith("Questions and Answers");
    }

    // htmlToText replaced a remaining inline tag with a single space unconditionally, which
    // split words that only had a tag (e.g. a formatting <font>) between two word characters.
    @Test void inlineTagBetweenWordCharactersDoesNotSplitTheWord() {
        String html = "<p>char<font>ter</font> of the company</p>";
        var ex = FilingTextExtractor.extract(html);
        assertThat(ex.text()).contains("charter");
        assertThat(ex.text()).doesNotContain("char ter");
    }

    // Table cells are block-level content, not inline runs — without a newline between them,
    // adjacent <td> text glues into a single fused word once tags are stripped.
    @Test void tableCellsDoNotGlueWordsTogether() {
        String html = "<table><tr><td>Ford</td><td>Motor</td></tr></table>";
        var ex = FilingTextExtractor.extract(html);
        assertThat(ex.text()).doesNotContain("FordMotor");
    }

    // The one-arg overload must remain behaviorally identical to explicit SECTION mode.
    @Test void defaultOverloadStillMeansSection() {
        String html = "<p>"
                + "a".repeat(80)
                + " one share for every two shares "
                + "b".repeat(80)
                + "</p><p>Questions and Answers</p><p>"
                + "c".repeat(80)
                + "</p>";
        var viaDefault = FilingTextExtractor.extract(html);
        var viaSection = FilingTextExtractor.extract(html, FilingTextExtractor.Mode.SECTION);
        assertThat(viaDefault).isEqualTo(viaSection);
    }

    // Low: heading matcher must skip a table-of-contents hit and land on the real section
    // heading further down (typical 10-K/DEFM14A structure: TOC lists the heading first).
    @Test void skipsTableOfContentsHeadingHit() {
        String html = "<html><body>"
                + "<p>Table of Contents</p>"
                + "<p>Summary Term Sheet ................. 5</p>"
                + "<p>Risk Factors ................. 12</p>"
                + "<p>filler text that pads the document out a bit more so the TOC and the real "
                + "section are clearly at different offsets in the document</p>"
                + "<p>SUMMARY TERM SHEET</p>"
                + "<p>The offer is $52.00 in cash per share.</p>"
                + "</body></html>";
        var ex = FilingTextExtractor.extract(html);
        assertThat(ex.sectionFound()).isTrue();
        assertThat(ex.text()).startsWith("SUMMARY TERM SHEET");
        assertThat(ex.text()).doesNotContain(".................");
        assertThat(ex.text()).contains("$52.00");
    }
}
