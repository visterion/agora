package de.visterion.agora.research;

import java.math.BigDecimal;
import java.util.List;

/** Default parameters bound from agora.research.* — only what the remaining
 *  non-catalog tools need (get_r_framework). Indicator params live in the
 *  indicator catalog (indicators-catalog.yaml / BuiltinIndicators). */
public record ResearchDefaults(
        BigDecimal rAtrMultiple, List<BigDecimal> rMultiples,
        int fetchDays) {
    public ResearchDefaults {
        rMultiples = List.copyOf(rMultiples);
    }
}
