package de.visterion.agora.fetch.search;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses Yahoo's /v1/finance/search payload into {@link SearchHit}s.
 *
 * <p>Filtering is by EXCLUSION, not by an EQUITY/ETF allow-list: Yahoo types exchange-listed
 * UCITS ETFs as MUTUALFUND (measured: XUEN.SG, Stuttgart), and Agora quotes them fine. An
 * allow-list would delete tradable instruments from the search.
 */
public final class YahooSearchParser {

    private static final Set<String> EXCLUDED_TYPES =
            Set.of("INDEX", "FUTURE", "CURRENCY", "CRYPTOCURRENCY");

    private YahooSearchParser() {}

    /** Filters and dedupes, preserving Yahoo's score order. Does NOT truncate. */
    public static List<SearchHit> parse(JsonNode payload) {
        List<SearchHit> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode q : payload.path("quotes")) {
            String symbol = q.path("symbol").asString("").trim();
            if (symbol.isEmpty()) continue;

            String type = q.path("quoteType").asString("").trim().toUpperCase(Locale.ROOT);
            if (EXCLUDED_TYPES.contains(type)) continue;

            if (!seen.add(symbol)) continue;

            String name = firstNonBlank(
                    q.path("longname").asString(""),
                    q.path("shortname").asString(""),
                    symbol);
            out.add(new SearchHit(symbol, name, q.path("exchDisp").asString(""), type));
        }
        return out;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c.trim();
        }
        return "";
    }
}
