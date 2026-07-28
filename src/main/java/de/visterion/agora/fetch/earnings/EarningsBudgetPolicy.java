package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.DataHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place the earnings timeout arithmetic exists.
 *
 * <p>D16 requires that a single provider attempt fits <em>strictly</em> inside the merge budget:
 * only then does a hanging provider surface as a real per-attempt failure (which trips the
 * cooldown) instead of a budget cancellation (which deliberately does not — see
 * {@link EarningsService}). Before this class the guarantee was asserted in prose while three
 * unrelated properties quietly broke it: the merge budget (7000 ms), the earnings read timeout
 * (4000 ms), {@link DataHttp#CONNECT_TIMEOUT_MS} (3000 ms, never counted at all) and the Finnhub
 * limiter's bounded wait (3000 ms, consumed <em>before</em> the request) summed to 10 000 ms.
 * A Finnhub that hung after connect was therefore always budget-cancelled, never cooled, and
 * re-hammered on every request forever.
 *
 * <p>The relationship is therefore expressed as a derivation plus a startup assertion rather
 * than as three numbers that happen to add up:
 *
 * <pre>
 *   limiterWaitMs = budgetMs - CONNECT_TIMEOUT_MS - attemptTimeoutMs - SAFETY_MARGIN_MS
 * </pre>
 *
 * <p>so the worst-case attempt — full limiter wait, then a full connect timeout, then a full read
 * timeout — is {@code budgetMs - SAFETY_MARGIN_MS}, i.e. strictly below the budget with room for
 * the fan-out's own bookkeeping. Editing {@code budget-ms} or {@code attempt-timeout-ms} into a
 * combination that leaves no usable wait fails the context start with an explicit message instead
 * of silently reinstating the bug.
 */
@Component
public class EarningsBudgetPolicy {

    /**
     * Headroom between the worst-case attempt and the budget, covering thread scheduling, the
     * merge itself and the fan-out's own bookkeeping — the guarantee is "strictly below", and a
     * zero-margin tie is not strictly below.
     */
    static final long SAFETY_MARGIN_MS = 500L;

    /** Below this a bounded wait is pointless (it would expire before the limiter can refill a
     *  token), so a configuration that derives less is rejected rather than quietly accepted. */
    static final long MIN_LIMITER_WAIT_MS = 250L;

    private final long budgetMs;
    private final long attemptTimeoutMs;
    private final long limiterWaitMs;

    @Autowired
    public EarningsBudgetPolicy(
            @Value("${agora.fetch.earnings.budget-ms:9000}") long budgetMs,
            @Value("${agora.fetch.earnings.attempt-timeout-ms:2500}") long attemptTimeoutMs) {
        this.budgetMs = budgetMs;
        this.attemptTimeoutMs = attemptTimeoutMs;
        this.limiterWaitMs = budgetMs - DataHttp.CONNECT_TIMEOUT_MS - attemptTimeoutMs - SAFETY_MARGIN_MS;
        if (limiterWaitMs < MIN_LIMITER_WAIT_MS) {
            throw new IllegalStateException(
                    "earnings budget arithmetic broken: agora.fetch.earnings.budget-ms=" + budgetMs
                            + " must exceed connect(" + DataHttp.CONNECT_TIMEOUT_MS + ") + attempt-timeout-ms("
                            + attemptTimeoutMs + ") + margin(" + SAFETY_MARGIN_MS + ") + minimum limiter wait("
                            + MIN_LIMITER_WAIT_MS + ") = "
                            + (DataHttp.CONNECT_TIMEOUT_MS + attemptTimeoutMs + SAFETY_MARGIN_MS + MIN_LIMITER_WAIT_MS)
                            + "ms; raise budget-ms or lower attempt-timeout-ms");
        }
    }

    /** Total wall-clock budget for one merged earnings call, spanning both fan-out phases. */
    public long budgetMs() { return budgetMs; }

    /** Read timeout for a single provider attempt. */
    public long attemptTimeoutMs() { return attemptTimeoutMs; }

    /**
     * Bounded wait an earnings caller may spend inside {@code FinnhubRateLimiter} — derived from
     * the budget rather than taken from the limiter's global {@code max-wait-ms}, which belongs to
     * callers (e.g. {@code FinnhubClient}) that have no merge budget to fit inside. This is spec
     * §6's "capped by the remaining call budget", expressed as a per-caller cap.
     */
    public long limiterWaitMs() { return limiterWaitMs; }

    /**
     * Worst case for a single HTTP attempt that does <em>not</em> go through the Finnhub limiter
     * (i.e. Nasdaq): a full connect timeout followed by a full read timeout. A provider that
     * loops over several requests uses this to decide whether another one can still finish
     * inside the budget, so it can report truncation instead of being budget-cancelled.
     */
    public long unthrottledAttemptWorstCaseMs() { return DataHttp.CONNECT_TIMEOUT_MS + attemptTimeoutMs; }
}
