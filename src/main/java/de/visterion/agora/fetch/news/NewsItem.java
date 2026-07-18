package de.visterion.agora.fetch.news;

import java.time.Instant;

/**
 * One news headline from a data source.
 * {@code sourceType} is a media-type label: {@code "news"} (editorial/wire) or
 * {@code "social"} (user-generated, e.g. forum posts). {@code datetime} may be null
 * when the source did not carry a parseable timestamp. {@code domain} is the
 * lowercase URL host without a leading {@code www.} prefix, or null for
 * blank/unparsable URLs; it is derived centrally by {@link NewsAggregator} —
 * providers always pass null (via the domain-free convenience constructor).
 */
public record NewsItem(String headline, String summary, String source,
                       String sourceType, Instant datetime, String url, String domain) {

    /** Domain-free convenience constructor for providers: only the aggregator sets {@code domain}. */
    public NewsItem(String headline, String summary, String source,
                    String sourceType, Instant datetime, String url) {
        this(headline, summary, source, sourceType, datetime, url, null);
    }
}
