package de.visterion.agora.fetch.earnings;

import java.util.List;

/**
 * One provider's answer for one call, plus whether that provider had to cut the window short.
 *
 * <p>{@code truncated} exists as a <em>return value</em> rather than as provider state on purpose.
 * The first shape of this was a {@code volatile boolean lastCallTruncated} field on the (singleton)
 * Nasdaq provider: with concurrent calls for different windows — which is exactly what
 * {@link EarningsService}'s fan-out and Agora's per-symbol batch runs produce — a wide window's
 * truncation could be read back as a narrow window's result, and vice versa. Truncation belongs to
 * the call, so it travels with the call's data.
 *
 * @param events    the events this provider could see for the requested window
 * @param truncated true when the provider stopped short of the requested window (day cap or
 *                  budget), so {@code events} is a partial view and the merged result must be
 *                  flagged {@code partial} rather than cached as a complete answer
 */
public record ProviderEarnings(List<EarningsEvent> events, boolean truncated) {

    /** A complete answer: the provider saw the whole requested window. */
    public static ProviderEarnings complete(List<EarningsEvent> events) {
        return new ProviderEarnings(events, false);
    }
}
