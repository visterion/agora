package de.visterion.agora.fetch.edgar;

import java.time.LocalDate;

/** One SEC full-text-search filing hit. ticker may be empty (e.g. fresh registrations). */
public record FilingHit(String ticker, String company, String form, LocalDate filedDate,
                        String accession, String url) {}
