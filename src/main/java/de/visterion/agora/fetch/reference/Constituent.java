package de.visterion.agora.fetch.reference;

import java.time.LocalDate;

/** One index constituent. sector/dateAdded may be null. */
public record Constituent(String symbol, String name, String sector, LocalDate dateAdded) {}
