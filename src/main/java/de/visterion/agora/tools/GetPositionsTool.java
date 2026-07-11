package de.visterion.agora.tools;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import de.visterion.agora.trading.BrokerException;
import de.visterion.agora.trading.BrokerService;
import de.visterion.agora.trading.Position;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetPositionsTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetPositionsTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "get_positions"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "List all open positions held by the account on the named connection.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("connection").put("type", "string")
                .put("description", "Target connection id (see list_connections)");
        schema.putArray("required").add("connection");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("connection"))
            return ToolResult.unavailable("missing required argument: connection");
        String connection = args.get("connection").asString();
        try {
            List<Position> positions = broker.positions(connection);
            ObjectNode out = mapper.createObjectNode();
            ArrayNode arr = out.putArray("positions");
            for (Position p : positions) {
                ObjectNode node = arr.addObject();
                node.put("symbol", p.symbol());
                node.put("qty", p.qty());
                node.put("avgEntryPrice", p.avgEntryPrice());
                node.put("marketValue", p.marketValue());
                node.put("unrealizedPl", p.unrealizedPl());
                node.put("currency", p.currency());
            }
            out.put("asOf", java.time.Instant.now().toString());
            return ToolResult.ok(out);
        } catch (BrokerException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
