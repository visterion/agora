package de.visterion.agora.fetch.earnings;

import java.time.LocalDate;
import java.util.List;

/** Pluggable earnings-calendar source. Throw MarketDataException(UNAVAILABLE) to yield to fallback. */
public interface EarningsProvider {
    String name();
    List<EarningsEvent> earnings(String symbol, LocalDate from, LocalDate to);

    /** Which portion of a window this source can answer; overridden by future-only sources. */
    default EarningsCoverage coverage() { return EarningsCoverage.FULL_WINDOW; }

    /**
     * The same fetch, bounded by the caller's total call budget and reporting whether the window
     * had to be cut short. Overridden only by sources whose cost grows with the window (Nasdaq
     * issues one request per calendar day); a single-request source always sees the whole window,
     * so the default delegates and reports no truncation.
     *
     * @param budgetMs wall-clock budget the caller allots to this attempt. A multi-request source
     *                 must stop and report {@code truncated} rather than run past it: a budget
     *                 cancellation discards the days already fetched <em>and</em> deliberately
     *                 does not trip the cooldown, so it can repeat indefinitely.
     */
    default ProviderEarnings earnings(String symbol, LocalDate from, LocalDate to, long budgetMs) {
        return ProviderEarnings.complete(earnings(symbol, from, to));
    }
}
