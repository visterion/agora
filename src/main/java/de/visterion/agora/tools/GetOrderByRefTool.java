package de.visterion.agora.tools;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import de.visterion.agora.trading.BrokerException;
import de.visterion.agora.trading.BrokerService;
import de.visterion.agora.trading.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GetOrderByRefTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetOrderByRefTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "get_order_by_ref"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "Look up an order by client reference ID (clientRef / client_order_id) on the named connection.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("connection").put("type", "string")
                .put("description", "Target connection id (see list_connections)");
        props.putObject("clientRef").put("type", "string").put("description", "Client reference ID");
        schema.putArray("required").add("connection").add("clientRef");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("connection"))
            return ToolResult.unavailable("missing required argument: connection");
        String connection = args.get("connection").asString();
        if (!args.hasNonNull("clientRef"))
            return ToolResult.unavailable("missing required argument: clientRef");

        String clientRef = args.get("clientRef").asString();

        try {
            Order o = broker.orderByClientRef(connection, clientRef);
            ObjectNode out = mapper.createObjectNode();
            ObjectNode order = out.putObject("order");
            order.put("brokerOrderId", o.brokerOrderId());
            order.put("clientRef", o.clientRef());
            order.put("symbol", o.symbol());
            order.put("side", o.side());
            order.put("qty", o.qty());
            order.put("type", o.type());
            order.put("status", o.status());
            order.put("role", o.role());
            if (o.filledQty() != null) order.put("filledQty", o.filledQty());
            if (o.avgFillPrice() != null) order.put("avgFillPrice", o.avgFillPrice());
            if (o.parentId() != null) order.put("parentId", o.parentId());
            return ToolResult.ok(out);
        } catch (BrokerException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
