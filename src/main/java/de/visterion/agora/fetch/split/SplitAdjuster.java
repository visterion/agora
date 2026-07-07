package de.visterion.agora.fetch.split;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;

/** Pure split-adjustment math for as-reported EPS series. */
public final class SplitAdjuster {
    private static final MathContext MC = MathContext.DECIMAL64;
    private SplitAdjuster() {}

    /** Product of forward-split ratios (toFactor/fromFactor) for splits dated AFTER periodEnd. */
    public static BigDecimal cumulativeFactorAfter(LocalDate periodEnd, List<SplitEvent> splits) {
        BigDecimal f = BigDecimal.ONE;
        for (SplitEvent s : splits) {
            if (s.date().isAfter(periodEnd)) {
                f = f.multiply(s.toFactor().divide(s.fromFactor(), MC), MC);
            }
        }
        return f;
    }

    public static BigDecimal adjust(BigDecimal reportedEps, BigDecimal cumulativeFactor) {
        return reportedEps.divide(cumulativeFactor, MC);
    }
}
