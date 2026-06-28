package de.visterion.agora.tool;

import tools.jackson.databind.JsonNode;

/** Normalised tool outcome. available=false means the tool ran but the upstream
 *  source was unavailable (graceful degradation); never used for auth/404. */
public record ToolResult(JsonNode output, boolean available, String error) {
    public ToolResult {
        if (available && output == null) throw new IllegalArgumentException("available=true requires non-null output");
        if (!available && error == null) throw new IllegalArgumentException("available=false requires non-null error");
    }
    public static ToolResult ok(JsonNode output) { return new ToolResult(output, true, null); }
    public static ToolResult unavailable(String error) { return new ToolResult(null, false, error); }
}
