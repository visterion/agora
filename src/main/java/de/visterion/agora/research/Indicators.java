package de.visterion.agora.research;

import java.math.BigDecimal;

/** Pure technical-analysis outputs. *Available flags false when history is too short. */
public record Indicators(
        BigDecimal currentClose,
        BigDecimal atr, boolean atrAvailable,
        BigDecimal chandelierStop, boolean chandelierBreached,
        BigDecimal maFast, boolean maFastAvailable,
        BigDecimal maSlow, boolean maSlowAvailable,
        String maCrossState,                 // "DEATH_CROSS" | "BULLISH" | "NEUTRAL"
        BigDecimal high52w, BigDecimal low52w, boolean window52wAvailable) {}
