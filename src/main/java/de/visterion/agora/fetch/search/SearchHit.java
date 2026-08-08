package de.visterion.agora.fetch.search;

/** One instrument search result. No currency — the search payload carries none. */
public record SearchHit(String symbol, String name, String exchange, String type) {}
