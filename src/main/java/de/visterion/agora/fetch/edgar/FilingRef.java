package de.visterion.agora.fetch.edgar;

import java.time.LocalDate;

/** A reference to one SEC filing. reportDate may be null. */
public record FilingRef(String accession, String form, LocalDate filedDate, LocalDate reportDate,
                        String primaryDoc, String url) {}
