package de.visterion.agora.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** A pluggable tool. Implement this as a Spring @Component to add a tool to BOTH
 *  the MCP endpoint and the Vistierie webhook — no consumer change required. */
public interface AgoraTool {
    String name();
    String description();
    ObjectNode inputSchema();          // JSON Schema for the tool's arguments
    ToolResult call(JsonNode args);
}
