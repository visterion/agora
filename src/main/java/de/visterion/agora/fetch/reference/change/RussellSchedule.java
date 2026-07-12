package de.visterion.agora.fetch.reference.change;

import java.time.LocalDate;

/**
 * One year's FTSE Russell US annual reconstitution key dates.
 *
 * <ul>
 *   <li>{@code rankDay} — the ranking snapshot (last business day of April).</li>
 *   <li>{@code preliminaryDate} — preliminary additions/deletions lists published (~late May,
 *       ~5 weeks before effective); the announcement anchor.</li>
 *   <li>{@code effectiveDate} — reconstitution effective (the last Friday of June); the date that
 *       appears in the final-list PDF file names.</li>
 * </ul>
 */
record RussellSchedule(int year, LocalDate rankDay, LocalDate preliminaryDate, LocalDate effectiveDate) {}
