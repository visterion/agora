package de.visterion.agora.fetch.search;

import java.util.function.LongSupplier;

/**
 * Reactive, single-source cooldown for the instrument search.
 *
 * <p>Deliberately NOT fetch/earnings/ProviderCooldown: that one is typed to
 * {@code Map<EarningsProvider, State>} and there is exactly one source here. Deliberately NOT
 * a min-interval either — the RssNewsProvider min-interval pattern aborts calls INSIDE the
 * interval, which on a typing burst would kill the last, wanted query and let the first three
 * through.
 */
class SearchCooldown {

    private final int threshold;
    private final long cooldownMillis;
    private final LongSupplier now;

    private int consecutiveFailures;
    private long cooledUntilMillis;

    SearchCooldown(int threshold, long cooldownMillis, LongSupplier now) {
        this.threshold = threshold;
        this.cooldownMillis = cooldownMillis;
        this.now = now;
    }

    synchronized boolean isCooled() {
        return now.getAsLong() < cooledUntilMillis;
    }

    synchronized void recordSuccess() {
        consecutiveFailures = 0;
        cooledUntilMillis = 0L;
    }

    synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= threshold) {
            cooledUntilMillis = now.getAsLong() + cooldownMillis;
            consecutiveFailures = 0;
        }
    }
}
