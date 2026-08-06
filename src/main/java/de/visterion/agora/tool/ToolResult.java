package de.visterion.agora.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Normalised tool outcome. available=false means the tool ran but the upstream
 *  source was unavailable (graceful degradation); never used for auth/404. */
public record ToolResult(JsonNode output, boolean available, String error) {
    public ToolResult {
        if (available && output == null) throw new IllegalArgumentException("available=true requires non-null output");
        if (!available && error == null) throw new IllegalArgumentException("available=false requires non-null error");
    }
    public static ToolResult ok(JsonNode output) { return new ToolResult(output, true, null); }
    public static ToolResult unavailable(String error) { return new ToolResult(null, false, error); }

    /**
     * A {@code NOT_FOUND} at the tool boundary: the tool ran, the source answered, and THIS ONE
     * item simply has nothing to serve. That is a statement about the item, never about the
     * source — so it must not leave as an error envelope ({@code McpToolAdapter} sets
     * {@code isError} for every unavailable result, and consumers read {@code isError} as an
     * outage; five healthy positions were logged as "Agora unreachable" that way).
     *
     * <p>Shape: an AVAILABLE result whose payload carries {@code available:false} plus an
     * {@code error} reason — exactly the shape {@code get_indicators} /
     * {@code get_indicators_batch} already emit via {@code IndicatorEvaluator.unavailable}, and
     * the one consumers are already documented to read as a data statement. Callers pass a
     * payload that is otherwise well-formed and empty (empty {@code bars}, empty {@code metrics}),
     * so nothing has to special-case a missing container.
     *
     * <p>{@code UNAVAILABLE} (and {@code RATE_LIMITED} / {@code TOO_LARGE}) still go through
     * {@link #unavailable(String)} and still produce an error envelope.
     */
    public static ToolResult noData(ObjectNode payload, String reason) {
        payload.put("available", false);
        payload.put("error", reason);
        return ok(payload);
    }
}
