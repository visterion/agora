package de.visterion.agora.fetch.finnhub;

import tools.jackson.databind.JsonNode;

/** Raw Finnhub company-profile object for a symbol (passthrough; consumer interprets). */
public record Profile(String symbol, JsonNode profile) {}
