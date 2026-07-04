package de.visterion.agora.fetch.earnings;

import java.time.LocalDate;
import java.util.List;

/** Pluggable earnings-calendar source. Throw MarketDataException(UNAVAILABLE) to yield to fallback. */
public interface EarningsProvider {
    String name();
    List<EarningsEvent> earnings(String symbol, LocalDate from, LocalDate to);
}
