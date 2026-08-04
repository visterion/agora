package de.visterion.agora.fetch.reference.change;

import java.time.LocalDate;

/**
 * One constituent change for a stock index: a symbol added to or removed from the index,
 * with the announcement and effective dates and an opaque provenance {@code source}.
 *
 * <p>{@code action} is {@code "add"} or {@code "remove"}. {@code source} is a neutral
 * provider tag (e.g. {@code "sp_press"}); Agora attaches no domain framing to it — the
 * consumer decides what a source means.
 *
 * <p>{@code companyName} is the issuer name as printed by the source (the S&amp;P press-release
 * prose, the FTSE Russell reconstitution list). It is <b>best effort</b>: a provider that
 * cannot resolve a name for a row emits {@code null} rather than a guess — a wrong issuer name
 * is worse than none. Consumers must treat it as nullable.
 */
public record IndexChange(
        String symbol,
        String companyName,
        String action,
        String index,
        LocalDate announcementDate,
        LocalDate effectiveDate,
        String source) {

    /**
     * Name-less construction, for a provider (or a test) that has no issuer name for the row.
     * Keeps the historic 6-argument shape valid instead of forcing an explicit {@code null}
     * at every call site that genuinely has nothing to say.
     */
    public IndexChange(String symbol, String action, String index,
            LocalDate announcementDate, LocalDate effectiveDate, String source) {
        this(symbol, null, action, index, announcementDate, effectiveDate, source);
    }
}
