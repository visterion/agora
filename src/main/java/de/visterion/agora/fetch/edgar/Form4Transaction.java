package de.visterion.agora.fetch.edgar;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One non-derivative Form-4 (or 4/A amendment) transaction (statement of changes in beneficial
 * ownership). {@code acquiredDisposedCode} is SEC's "A"/"D" (acquired/disposed) flag;
 * {@code form} is the filing's form type ("4" or "4/A") so callers can see amendments.
 *
 * <p>Fail-soft nullable fields (all parsed from the Form-4 XML, absent/unparsable → null):
 * <ul>
 *   <li>{@code price} — per-share transaction price ({@code transactionPricePerShare}); can be
 *       legitimately absent (e.g. gifts, footnote-only values). {@code dollarValue} treats a
 *       missing price as 0. Intentional change (2026-07): an unparsable price no longer discards
 *       the whole filing — the transaction is kept with {@code price=null}/{@code dollarValue=0}
 *       (previously such filings were skipped entirely).</li>
 *   <li>{@code sharesOwnedFollowing} — {@code sharesOwnedFollowingTransaction} from the
 *       transaction's {@code postTransactionAmounts} block.</li>
 *   <li>{@code aff10b5One} — the filing-level Rule 10b5-1(c) checkbox (XML element
 *       {@code aff10b5One}, mandatory on filings since 2023). Tri-state: {@code TRUE}/{@code FALSE}
 *       when the filing carries the element, {@code null} when it doesn't (pre-2023 filings) —
 *       null means "unknown", NOT "not a plan transaction".</li>
 * </ul>
 * {@code filerCik} is the first reporting owner's CIK ({@code rptOwnerCik}, zero-padded as filed);
 * empty when absent.
 */
public record Form4Transaction(String ticker, String filerName, String filerRole,
                               LocalDate transactionDate, BigDecimal shares, BigDecimal dollarValue,
                               String code, String acquiredDisposedCode, String form,
                               BigDecimal price, BigDecimal sharesOwnedFollowing,
                               Boolean aff10b5One, String filerCik) {}
