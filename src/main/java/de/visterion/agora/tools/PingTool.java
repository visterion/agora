package de.visterion.agora.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;

/** Trivial health/liveness tool — proves both front-doors end-to-end. */
@Component
public class PingTool implements AgoraTool {

    private final ObjectMapper mapper = new ObjectMapper();

    public String name() { return "ping"; }

    public String description() { return "Returns pong plus any echoed message; liveness probe for tooling."; }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("message").put("type", "string").put("description", "optional text to echo back");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        ObjectNode out = mapper.createObjectNode();
        out.put("pong", true);
        if (args != null && args.hasNonNull("message")) {
            out.put("message", args.get("message").asString());
        }
        return ToolResult.ok(out);
    }
}
