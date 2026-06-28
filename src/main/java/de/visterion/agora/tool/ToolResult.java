package de.visterion.agora.tool;

import tools.jackson.databind.JsonNode;

/** Normalised tool outcome. available=false means the tool ran but the upstream
 *  source was unavailable (graceful degradation); never used for auth/404. */
public record ToolResult(JsonNode output, boolean available, String error) {
    public static ToolResult ok(JsonNode output) { return new ToolResult(output, true, null); }
    public static ToolResult unavailable(String error) { return new ToolResult(null, false, error); }
}
