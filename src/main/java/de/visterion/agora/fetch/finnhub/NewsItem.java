package de.visterion.agora.fetch.finnhub;

import java.time.Instant;

/** One news headline from a data source. */
public record NewsItem(String headline, String summary, String source, Instant datetime, String url) {}
