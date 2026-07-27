package de.visterion.agora.fetch.earnings;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Consecutive-failure cooldown for earnings providers, so a dead source stops being asked
 * instead of burning a failed call on every request — while still self-healing when it
 * recovers.
 *
 * <p>Deliberately NOT a static, name-keyed registry like {@code RssNewsProvider}'s
 * HOST_COOLDOWN: {@code name()} collides across provider families ("finnhub" and "yahoo"
 * each name two unrelated classes), so state is keyed by provider identity and owned by the
 * service instance that holds it.
 */
public class ProviderCooldown {

    private record State(int consecutiveFailures, long cooledUntilMillis) {}

    private final int threshold;
    private final long cooldownMillis;
    private final LongSupplier now;
    private final Map<EarningsProvider, State> states = new IdentityHashMap<>();

    public ProviderCooldown(int threshold, long cooldownMillis, LongSupplier now) {
        this.threshold = threshold;
        this.cooldownMillis = cooldownMillis;
        this.now = now;
    }

    public synchronized boolean isCooled(EarningsProvider p) {
        State s = states.get(p);
        return s != null && s.cooledUntilMillis() > now.getAsLong();
    }

    public synchronized void recordFailure(EarningsProvider p) {
        State s = states.getOrDefault(p, new State(0, 0L));
        int failures = s.consecutiveFailures() + 1;
        long until = failures >= threshold ? now.getAsLong() + cooldownMillis : s.cooledUntilMillis();
        states.put(p, new State(failures, until));
    }

    public synchronized void recordSuccess(EarningsProvider p) {
        states.remove(p);
    }
}
