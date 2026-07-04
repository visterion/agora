package de.visterion.agora.research;

import java.math.BigDecimal;
import java.util.List;

/** Default indicator parameters bound from agora.research.* (Slice 6 additions). */
public record ResearchDefaults(
        int rsiPeriod,
        int macdFast, int macdSlow, int macdSignal,
        int bollingerPeriod, BigDecimal bollingerK,
        int stochasticK, int stochasticD,
        int adxPeriod,
        int cciPeriod,
        int williamsPeriod,
        BigDecimal rAtrMultiple, List<BigDecimal> rMultiples,
        int fetchDays) {
    public ResearchDefaults {
        rMultiples = List.copyOf(rMultiples);
    }
}
