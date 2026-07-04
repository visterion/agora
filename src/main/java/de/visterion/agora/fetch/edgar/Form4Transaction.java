package de.visterion.agora.fetch.edgar;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One non-derivative Form-4 transaction (statement of changes in beneficial ownership). */
public record Form4Transaction(String ticker, String filerName, String filerRole,
                               LocalDate transactionDate, BigDecimal shares, BigDecimal dollarValue,
                               String code) {}
