package de.visterion.agora.data;

import java.net.SocketTimeoutException;

/**
 * H8b: turns a caught transport exception into a client-safe category message. Never surface
 * {@code e.getMessage()} for a transport-layer failure — Spring's {@code ResourceAccessException}
 * (and friends) embed the full request URI, which may carry an API key in a query parameter.
 * Callers must log the original exception server-side (WARN) themselves; this only sanitizes the
 * message that flows into {@link MarketDataException} / client-facing {@code ToolResult}s.
 */
public final class ProviderErrors {

    private ProviderErrors() { }

    /** Returns "{@code <provider> timeout}" if a {@link SocketTimeoutException} is anywhere in the
     *  cause chain, otherwise "{@code <provider> request failed}". Never echoes {@code e.getMessage()}. */
    public static String categorize(String provider, Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) return provider + " timeout";
        }
        return provider + " request failed";
    }
}
