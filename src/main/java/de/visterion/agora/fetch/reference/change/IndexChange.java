package de.visterion.agora.fetch.reference.change;

import java.time.LocalDate;

/**
 * One constituent change for a stock index: a symbol added to or removed from the index,
 * with the announcement and effective dates and an opaque provenance {@code source}.
 *
 * <p>{@code action} is {@code "add"} or {@code "remove"}. {@code source} is a neutral
 * provider tag (e.g. {@code "sp_press"}); Agora attaches no domain framing to it — the
 * consumer decides what a source means.
 */
public record IndexChange(
        String symbol,
        String action,
        String index,
        LocalDate announcementDate,
        LocalDate effectiveDate,
        String source) {}
