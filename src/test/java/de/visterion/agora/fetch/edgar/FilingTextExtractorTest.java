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
}
