package de.visterion.agora.fetch.edgar;

import java.util.List;
import java.util.Map;

/**
 * Turns a raw SEC filing document (HTML or text) into the plain-English "Summary Term
 * Sheet" section when present, else a leading text window — truncated to a budget.
 * Pure and side-effect-free: no I/O, no interpretation. SEC requires DEFM14A / SC TO-T /
 * 10-12B filings to carry a plain-English summary near the front.
 */
public final class FilingTextExtractor {

    /** Hard character budget on the returned slice (~6k tokens). */
    public static final int MAX_CHARS = 24_000;

    // Lower-case section headings, tried in order; the LAST match in the document wins (typical
    // 10-K/DEFM14A structure lists these in a table of contents near the top first, then again
    // as the real section heading further down — taking the last occurrence skips the TOC hit).
    private static final List<String> HEADINGS = List.of(
            "summary term sheet", "summary of the transaction", "questions and answers");

    // Named HTML entities decoded in a single pass (case-sensitive, as HTML entities are).
    private static final Map<String, String> NAMED_ENTITIES = Map.of(
            "nbsp", " ", "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'");

    private FilingTextExtractor() {}

    public record Extract(String text, boolean sectionFound, boolean truncated) {}

    public static Extract extract(String rawDocument) {
        String text = htmlToText(rawDocument);
        int start = headingIndex(text);
        boolean sectionFound = start >= 0;
        String slice = sectionFound ? text.substring(start) : text;
        boolean truncated = slice.length() > MAX_CHARS;
        if (truncated) slice = slice.substring(0, MAX_CHARS);
        return new Extract(slice.strip(), sectionFound, truncated);
    }

    // Case-insensitive match without a toLowerCase() copy of the (multi-MB) document: regionMatches
    // scans in place. Takes the LAST occurrence of each heading (skips an earlier TOC hit), then the
    // earliest-positioned heading among those wins — mirroring the original "first heading in the
    // document" semantics while skipping TOC entries.
    private static int headingIndex(String text) {
        int best = -1;
        for (String h : HEADINGS) {
            int i = lastIndexOfIgnoreCase(text, h);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static int lastIndexOfIgnoreCase(String text, String needle) {
        int max = text.length() - needle.length();
        for (int i = max; i >= 0; i--) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    static String htmlToText(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");   // drop script/style bodies
        s = s.replaceAll("(?i)<(/?)(p|div|br|tr|h[1-6]|li|table)[^>]*>", "\n"); // block tags → newline
        s = s.replaceAll("(?s)<[^>]+>", " ");                           // strip remaining tags
        s = decodeEntities(s);                                          // single-pass entity decode
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");                    // collapse inline whitespace
        s = s.replaceAll(" *\\n *", "\n").replaceAll("\\n{2,}", "\n");  // collapse blank lines
        return s.strip();
    }

    // Single-pass entity decode via a small named-entity map plus numeric &#NN;/&#xHH; — replaces
    // the previous chain of 8 sequential full-string .replace() calls (each a separate O(n) pass).
    private static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i + 1);
                // Entity names are short; cap the lookahead so a stray '&' in prose (no real
                // entity) doesn't force a long scan for ';'.
                if (semi > i && semi - i <= 12) {
                    String decoded = decodeEntity(s.substring(i + 1, semi));
                    if (decoded != null) {
                        sb.append(decoded);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String decodeEntity(String name) {
        String named = NAMED_ENTITIES.get(name);
        if (named != null) return named;
        if (name.length() > 1 && name.charAt(0) == '#') {
            try {
                String numPart = name.substring(1);
                int codePoint;
                if (numPart.length() > 1 && (numPart.charAt(0) == 'x' || numPart.charAt(0) == 'X')) {
                    codePoint = Integer.parseInt(numPart.substring(1), 16);
                } else {
                    codePoint = Integer.parseInt(numPart);
                }
                // &#160; is a non-breaking space (U+00A0); normalize to a plain space so the
                // inline-whitespace collapse regex (which matches literal " ", not U+00A0) still
                // squashes runs of it, matching prior explicit &#160;->" " handling.
                if (codePoint == 160) return " ";
                return new String(Character.toChars(codePoint));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
