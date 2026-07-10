package de.visterion.agora.fetch.edgar;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One non-derivative Form-4 (or 4/A amendment) transaction (statement of changes in beneficial
 * ownership). {@code acquiredDisposedCode} is SEC's "A"/"D" (acquired/disposed) flag;
 * {@code form} is the filing's form type ("4" or "4/A") so callers can see amendments.
 */
public record Form4Transaction(String ticker, String filerName, String filerRole,
                               LocalDate transactionDate, BigDecimal shares, BigDecimal dollarValue,
                               String code, String acquiredDisposedCode, String form) {}
