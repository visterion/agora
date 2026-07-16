package de.visterion.agora.fetch.news;

import java.time.Instant;

/**
 * One news headline from a data source.
 * {@code sourceType} is a media-type label: {@code "news"} (editorial/wire) or
 * {@code "social"} (user-generated, e.g. forum posts). {@code datetime} may be null
 * when the source did not carry a parseable timestamp.
 */
public record NewsItem(String headline, String summary, String source,
                       String sourceType, Instant datetime, String url) {}
