package de.visterion.agora.research.fundamentals;

import java.math.BigDecimal;
import java.util.Map;

/** Piotroski (2000) F-score, strict + coverage. A criterion scores 1 only if met AND
 *  available; unavailable criteria score 0 but count toward criteriaAvailable's complement. */
public record PiotroskiFScore(int score, int criteriaAvailable,
                              Map<String, Criterion> criteria, Map<String, BigDecimal> raw) {
    public record Criterion(boolean met, boolean available) {}
}
