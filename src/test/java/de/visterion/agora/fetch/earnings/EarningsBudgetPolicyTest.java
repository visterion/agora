package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.DataHttp;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class EarningsBudgetPolicyTest {

    /**
     * I3: the shipped defaults (budget 7000, read timeout 4000) never counted the 3000 ms connect
     * timeout or the limiter's 3000 ms bounded wait, so a worst-case Finnhub attempt was 10 000 ms
     * against a 7000 ms budget — always budget-cancelled, and a budget cancellation deliberately
     * does not trip the cooldown, so it repeated forever. This pins the arithmetic itself rather
     * than the three numbers, so retuning any of them keeps the guarantee or fails the build.
     */
    @Test void worstCaseAttemptFitsStrictlyInsideTheBudget() {
        var policy = new EarningsBudgetPolicy(9_000L, 2_500L);

        long worstCase = policy.limiterWaitMs() + DataHttp.CONNECT_TIMEOUT_MS + policy.attemptTimeoutMs();

        assertThat(worstCase).isLessThan(policy.budgetMs());
        assertThat(policy.limiterWaitMs()).isGreaterThanOrEqualTo(EarningsBudgetPolicy.MIN_LIMITER_WAIT_MS);
    }

    @Test void unthrottledWorstCaseIsConnectPlusRead() {
        var policy = new EarningsBudgetPolicy(9_000L, 2_500L);

        assertThat(policy.unthrottledAttemptWorstCaseMs())
                .isEqualTo(DataHttp.CONNECT_TIMEOUT_MS + 2_500L);
        assertThat(policy.unthrottledAttemptWorstCaseMs()).isLessThan(policy.budgetMs());
    }

    @Test void aBudgetThatCannotHoldOneAttemptFailsFastAtStartup() {
        // The exact shipped-and-broken combination: 7000 budget, 4000 read timeout.
        assertThatThrownBy(() -> new EarningsBudgetPolicy(7_000L, 4_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget-ms")
                .hasMessageContaining("attempt-timeout-ms");
    }
}
