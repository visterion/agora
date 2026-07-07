package de.visterion.agora.tools;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import de.visterion.agora.trading.ConnectionRegistry;
import de.visterion.agora.trading.LiveAccessGuard;
import de.visterion.agora.trading.RegisteredConnection;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;

/** Token-scoped discovery of active trading connections. Live ids only for live tokens. */
@Component
public class ListConnectionsTool implements AgoraTool {

    private final ConnectionRegistry registry;
    private final LiveAccessGuard guard;
    private final ObjectMapper mapper = new ObjectMapper();

    public ListConnectionsTool(ConnectionRegistry registry, LiveAccessGuard guard) {
        this.registry = registry;
        this.guard = guard;
    }

    @Override public String name() { return "list_connections"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "List active trading connections (id, provider, environment, probe status) visible to the caller.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode arr = out.putArray("connections");
        for (RegisteredConnection c : registry.active()) {
            if (!guard.canSee(c)) continue;
            ObjectNode n = arr.addObject();
            n.put("id", c.id());
            n.put("provider", c.config().getProvider());
            n.put("environment", c.config().getEnvironment().name().toLowerCase(Locale.ROOT));
            n.put("status", c.probeStatus().state());
            if (c.probeStatus().probedAt() != null) {
                n.put("probedAt", c.probeStatus().probedAt().toString());
            }
        }
        return ToolResult.ok(out);
    }
}
