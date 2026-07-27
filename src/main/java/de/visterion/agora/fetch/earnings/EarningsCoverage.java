package de.visterion.agora.fetch.earnings;

import java.time.LocalDate;

/**
 * Which part of a requested window a provider is able to answer. This is what stops a
 * future-only source from reporting a vacuous "no earnings" success for an all-past
 * window — see D6/D7: the default get_earnings_window window is entirely in the past.
 */
public enum EarningsCoverage {
    /** Answers past and future days alike. */
    FULL_WINDOW,
    /** Only knows about scheduled (>= today) events; has no historical actuals. */
    FUTURE_ONLY;

    /** True when this coverage intersects [from, to] given today's US-Eastern date. */
    public boolean covers(LocalDate from, LocalDate to, LocalDate todayEt) {
        if (this == FULL_WINDOW) return true;
        return !to.isBefore(todayEt);
    }
}
