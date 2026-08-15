package de.visterion.agora.fetch.edgar;

import de.visterion.agora.data.MarketDataException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a SEC filing's index page ({@code {accession}-index.htm}) into its document table —
 * pure and side-effect-free, no I/O. This exists because neither of the two obvious shortcuts
 * works: {@code index.json}'s {@code type} field carries the ICON name ({@code text.gif}), not
 * the exhibit type, and {@code {accession}-headers.html} carries no document list at all. The
 * typed table only exists on the {@code -index.htm} page itself.
 */
public final class FilingIndex {

    private static final Pattern ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>");
    private static final Pattern SEQ = Pattern.compile("^\\d+$");
    private static final Pattern ACCESSION_NO_DASHES = Pattern.compile("^\\d{18}$");

    private FilingIndex() {}

    /** One row of the index page's document table: its Type column and its Document column. */
    public record Doc(String type, String name) {}

    /**
     * Parses the document table rows: a row counts as a document when it has five cells and its
     * first cell (the {@code Seq} column) is purely numeric — that excludes the header row and
     * any other content on the page. Returns an empty list when there is no such table (SEC's
     * generic error page, or any page with no document rows) — that is a legitimate, distinct
     * outcome from a page that failed to load at all, which throws instead (see
     * {@link EdgarSearchService#filingText(String, String, FilingTextExtractor.Mode)}).
     */
    public static List<Doc> parse(String indexHtml) {
        if (indexHtml == null || indexHtml.isBlank()) return List.of();
        List<Doc> docs = new ArrayList<>();
        Matcher rows = ROW.matcher(indexHtml);
        while (rows.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(rows.group(1));
            while (cell.find()) {
                cells.add(stripTags(cell.group(1)).strip());
            }
            if (cells.size() < 5) continue;
            if (!SEQ.matcher(cells.get(0)).matches()) continue;
            String name = cells.get(2);
            String type = cells.get(3);
            if (name.isEmpty() || type.isEmpty()) continue;
            docs.add(new Doc(type, name));
        }
        return docs;
    }

    private static String stripTags(String html) {
        return html.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    /**
     * Builds the DASHED accession form of a document's index page URL from a document URL in the
     * same folder. The dash-less form ({@code {accNoDashes}-index.html}) returns HTTP 503 with
     * SEC's generic error page — measured three times against two real accessions — so getting
     * this form wrong looks exactly like a rate-limiting failure, not like a "no index" result.
     *
     * <p>{@code documentUrl} comes from a model-supplied {@code get_filing_text} call, so it is
     * reachable-but-untrusted input. The folder segment directly above the document is expected
     * to be the 18-digit accession-no-dashes form; anything else (already-dashed, a short CIK
     * folder, non-numeric) throws {@link MarketDataException} rather than a raw
     * {@code StringIndexOutOfBoundsException} from a fixed-offset slice. There is deliberately no
     * fallback to the primary document here: a malformed URL is a caller bug, and swallowing it
     * would look identical to "this filing legitimately has no exhibit".
     */
    public static String indexUrl(String documentUrl) {
        int lastSlash = documentUrl.lastIndexOf('/');
        String dir = documentUrl.substring(0, lastSlash);
        int prevSlash = dir.lastIndexOf('/');
        String accNoDashes = dir.substring(prevSlash + 1);
        if (!ACCESSION_NO_DASHES.matcher(accNoDashes).matches()) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "not a filing document url (accession folder must be 18 digits, got '"
                            + accNoDashes + "'): " + documentUrl, null);
        }
        String dashed = accNoDashes.substring(0, 10) + "-" + accNoDashes.substring(10, 12) + "-"
                + accNoDashes.substring(12);
        return dir + "/" + dashed + "-index.htm";
    }
}
