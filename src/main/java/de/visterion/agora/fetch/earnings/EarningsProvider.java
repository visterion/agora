package de.visterion.agora.fetch.earnings;

import java.time.LocalDate;
import java.util.List;

/** Pluggable earnings-calendar source. Throw MarketDataException(UNAVAILABLE) to yield to fallback. */
public interface EarningsProvider {
    String name();
    List<EarningsEvent> earnings(String symbol, LocalDate from, LocalDate to);

    /** Which portion of a window this source can answer; overridden by future-only sources. */
    default EarningsCoverage coverage() { return EarningsCoverage.FULL_WINDOW; }
}
