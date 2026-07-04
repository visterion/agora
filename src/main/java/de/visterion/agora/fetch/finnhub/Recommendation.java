package de.visterion.agora.fetch.finnhub;

/** One analyst-recommendation snapshot for a period. */
public record Recommendation(String period, int strongBuy, int buy, int hold, int sell, int strongSell) {}
