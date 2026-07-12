package de.visterion.agora.fetch.reference.change;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the plain text extracted from an FTSE Russell reconstitution PDF (additions or
 * deletions list) into {@code (ticker, companyName)} rows. The PDF lays each constituent out as
 * {@code <COMPANY NAME>  <TICKER>  <INDUSTRY>}, one per line, repeated per page under a
 * {@code "Company Symbol Industry"} header, wrapped in page headers/footers and a legal
 * disclaimer.
 *
 * <p>The parse anchors on the industry classification at the end of the line — a fixed
 * vocabulary of Russell industry names — so page headers, footers and the legal boilerplate
 * (which never end in an industry name) are excluded without a fragile line-number/offset scheme.
 * The ticker is the last uppercase token immediately before the industry; the company name is
 * everything before it. Rows that do not match this shape are skipped (never half-parsed).
 *
 * <p><b>Observability against silent data loss:</b> for a research feed the worst failure mode is
 * a silently dropped constituent. Because the parse anchors on a fixed vocabulary, a future FTSE
 * relabel ("Telecom" vs "Telecommunications"), a new sub-sector, or a blank industry cell would
 * drop rows invisibly. To surface that, every line that has the <em>shape</em> of a constituent
 * row (a ticker-like token followed by a short title-cased tail) but whose tail is not a
 * recognised industry is counted and logged at WARN with truncated samples — the gap becomes
 * visible in the logs instead of vanishing.
 */
final class RussellConstituentListParser {

    private static final Logger log = LoggerFactory.getLogger(RussellConstituentListParser.class);

    /** Russell US industry classifications; anchoring on these separates data rows from noise. */
    private static final List<String> INDUSTRIES = List.of(
            "Basic Materials", "Consumer Discretionary", "Consumer Staples", "Energy",
            "Financials", "Health Care", "Industrials", "Real Estate", "Technology",
            "Telecommunications", "Utilities");

    // Longest-industry-first alternation so "Consumer Discretionary" is not shadowed by a shorter prefix.
    private static final String INDUSTRY_ALT = String.join("|", INDUSTRIES.stream()
            .sorted((a, b) -> b.length() - a.length())
            .map(Pattern::quote).toList());

    // A data row: <company (greedy)>  <ticker (A-Z start, up to 6 alnum/dot)>  <known industry>.
    private static final Pattern ROW = Pattern.compile(
            "^\\s*(?<name>.+?)\\s+(?<ticker>[A-Z][A-Z0-9.]{0,5})\\s+(?<industry>" + INDUSTRY_ALT + ")\\s*$");

    // A constituent-SHAPED row whose industry tail is any short title-cased phrase (1-3 words), not
    // necessarily a recognised one. Used only to detect rows the ROW pattern would silently drop
    // because the industry label is unknown. The company-name segment is required to be
    // upper-cased ([^a-z]: caps, digits, punctuation — as every real Russell company name is), which
    // keeps this from matching Title-cased page headers ("Russell US Indexes"), the column header
    // ("Company Symbol Industry") and lower-cased legal-prose sentences.
    private static final Pattern CONSTITUENT_SHAPED = Pattern.compile(
            "^\\s*[^a-z]+\\s+[A-Z][A-Z0-9.]{0,5}\\s+[A-Z][A-Za-z]+(?: [A-Z][A-Za-z]+){0,2}\\s*$");

    private static final int MAX_LOGGED_SAMPLES = 5;

    private RussellConstituentListParser() {}

    /** One parsed row: an upper-cased ticker and the company name exactly as printed. */
    record Row(String ticker, String companyName) {}

    /**
     * Parses every constituent row in the extracted text. Duplicate tickers (a name can repeat if
     * PDFBox emits an overlapping line) are collapsed, first occurrence wins. Never throws; a
     * blank/null input yields an empty list. Constituent-shaped lines with an unrecognised industry
     * label are dropped but counted and logged at WARN (see the class doc).
     */
    static List<Row> parse(String text) {
        List<Row> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        Set<String> seen = new LinkedHashSet<>();
        List<String> unrecognised = new ArrayList<>();

        for (String line : text.split("\\r?\\n")) {
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                String ticker = row.group("ticker").toUpperCase(Locale.ROOT);
                // The literal header "Company Symbol Industry" never matches (its tail "Industry" is
                // not in the vocabulary), so no explicit guard is needed.
                if (seen.add(ticker)) out.add(new Row(ticker, row.group("name").trim()));
            } else if (CONSTITUENT_SHAPED.matcher(line).matches()) {
                // Constituent-shaped but the industry tail was not recognised -> would be dropped
                // silently. Record it so the loss is observable.
                unrecognised.add(line.trim());
            }
        }

        if (!unrecognised.isEmpty()) {
            log.warn("Russell constituent parse: dropped {} constituent-shaped line(s) with an "
                            + "unrecognised industry label (possible FTSE relabel/new sub-sector); "
                            + "parsed {} row(s). Samples: {}",
                    unrecognised.size(), out.size(),
                    unrecognised.subList(0, Math.min(MAX_LOGGED_SAMPLES, unrecognised.size())));
        }
        return out;
    }
}
