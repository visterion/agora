package de.visterion.agora.fetch.edgar;

import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure parsing of a SEC filing index page — no I/O. All fixtures below are hand-written and
 * synthetic (invented company names, invented accession numbers); never a real SEC document.
 */
class FilingIndexTest {

    @Test void parsesTypeAndDocumentColumns() {
        String html = """
            <html><body>
            <table class="tableFile" summary="Document Format Files">
            <tr><th>Seq</th><th>Description</th><th>Document</th><th>Type</th><th>Size</th></tr>
            <tr><td>1</td><td>Registration Statement</td><td><a href="newco-1012ba.htm">newco-1012ba.htm</a></td><td>10-12B/A</td><td>88888</td></tr>
            <tr><td>2</td><td>Information Statement</td><td><a href="newco-ex991.htm">newco-ex991.htm</a></td><td>EX-99.1</td><td>3100000</td></tr>
            </table>
            </body></html>
            """;

        List<FilingIndex.Doc> docs = FilingIndex.parse(html);

        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).type()).isEqualTo("10-12B/A");
        assertThat(docs.get(0).name()).isEqualTo("newco-1012ba.htm");
        assertThat(docs.get(1).type()).isEqualTo("EX-99.1");
        assertThat(docs.get(1).name()).isEqualTo("newco-ex991.htm");
    }

    @Test void returnsEmptyListWhenThereIsNoDocumentTable() {
        String html = "<html><body>Error</body></html>";

        List<FilingIndex.Doc> docs = FilingIndex.parse(html);

        assertThat(docs).isEmpty();
    }

    @Test void buildsTheDashedAccessionForm() {
        String documentUrl = "https://www.sec.gov/Archives/edgar/data/1234/000121390026074253/foo.htm";

        String indexUrl = FilingIndex.indexUrl(documentUrl);

        assertThat(indexUrl).isEqualTo(
                "https://www.sec.gov/Archives/edgar/data/1234/000121390026074253/0001213900-26-074253-index.htm");
    }

    /**
     * {@code get_filing_text} accepts a model-supplied URL. A folder segment that is not the
     * expected 18-digit accession-no-dashes form must fail cleanly instead of a raw
     * {@code StringIndexOutOfBoundsException} from the fixed-offset {@code substring} calls —
     * two reachable shapes: an accession folder that already carries dashes (a document URL one
     * level too shallow) and a short numeric CIK folder mistaken for the accession folder.
     */
    @Test void rejectsAFolderSegmentThatIsNotAnEighteenDigitAccession() {
        assertThatThrownBy(() -> FilingIndex.indexUrl(
                "https://www.sec.gov/Archives/edgar/data/320193/0000320193-25-000123.txt"))
                .isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> FilingIndex.indexUrl(
                "https://www.sec.gov/Archives/edgar/data/12/both.htm"))
                .isInstanceOf(MarketDataException.class);
    }
}
