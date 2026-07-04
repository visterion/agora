package de.visterion.agora.fetch.finnhub;

import tools.jackson.databind.JsonNode;

/** Raw fundamentals metric object for a symbol (passthrough; consumer interprets). */
public record Fundamentals(String symbol, JsonNode metrics) {}
