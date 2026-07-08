package de.visterion.agora.fetch.edgar;

import java.util.List;
import java.util.Locale;

/**
 * Turns a raw SEC filing document (HTML or text) into the plain-English "Summary Term
 * Sheet" section when present, else a leading text window — truncated to a budget.
 * Pure and side-effect-free: no I/O, no interpretation. SEC requires DEFM14A / SC TO-T /
 * 10-12B filings to carry a plain-English summary near the front.
 */
public final class FilingTextExtractor {

    /** Hard character budget on the returned slice (~6k tokens). */
    public static final int MAX_CHARS = 24_000;

    // Lower-case section headings, tried in order; the earliest match in the document wins.
    private static final List<String> HEADINGS = List.of(
            "summary term sheet", "summary of the transaction", "questions and answers");

    private FilingTextExtractor() {}

    public record Extract(String text, boolean sectionFound, boolean truncated) {}

    public static Extract extract(String rawDocument) {
        String text = htmlToText(rawDocument);
        int start = headingIndex(text.toLowerCase(Locale.ROOT));
        boolean sectionFound = start >= 0;
        String slice = sectionFound ? text.substring(start) : text;
        boolean truncated = slice.length() > MAX_CHARS;
        if (truncated) slice = slice.substring(0, MAX_CHARS);
        return new Extract(slice.strip(), sectionFound, truncated);
    }

    private static int headingIndex(String lowerText) {
        int best = -1;
        for (String h : HEADINGS) {
            int i = lowerText.indexOf(h);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    static String htmlToText(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");   // drop script/style bodies
        s = s.replaceAll("(?i)<(/?)(p|div|br|tr|h[1-6]|li|table)[^>]*>", "\n"); // block tags → newline
        s = s.replaceAll("(?s)<[^>]+>", " ");                           // strip remaining tags
        s = s.replace("&nbsp;", " ").replace("&#160;", " ")
             .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&#8217;", "'").replace("&#8220;", "\"").replace("&#8221;", "\"");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");                    // collapse inline whitespace
        s = s.replaceAll(" *\\n *", "\n").replaceAll("\\n{2,}", "\n");  // collapse blank lines
        return s.strip();
    }
}
