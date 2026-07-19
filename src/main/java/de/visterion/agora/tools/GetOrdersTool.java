package de.visterion.agora.tools;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import de.visterion.agora.trading.BrokerException;
import de.visterion.agora.trading.BrokerService;
import de.visterion.agora.trading.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetOrdersTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetOrdersTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "get_orders"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "List all open and recent orders for the account on the named connection.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("connection").put("type", "string")
                .put("description", "Target connection id (see list_connections)");
        props.putObject("status").put("type", "string")
                .put("description", "Filter by order status (e.g. open, closed, all). Optional.");
        props.putObject("from").put("type", "string")
                .put("description", "ISO-8601 UTC lower bound on order submission time (broker-mapped, boundary-exclusive on Alpaca). Optional.");
        props.putObject("to").put("type", "string")
                .put("description", "ISO-8601 UTC upper bound on order submission time (broker-mapped). Optional.");
        schema.putArray("required").add("connection");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("connection"))
            return ToolResult.unavailable("missing required argument: connection");
        String connection = args.get("connection").asString();
        String status = args.hasNonNull("status")
                ? args.get("status").asString(null) : null;
        String from = args.hasNonNull("from") ? args.get("from").asString(null) : null;
        String to = args.hasNonNull("to") ? args.get("to").asString(null) : null;
        try {
            List<Order> orders = broker.orders(connection, status, from, to);
            ObjectNode out = mapper.createObjectNode();
            ArrayNode arr = out.putArray("orders");
            for (Order o : orders) {
                ObjectNode node = arr.addObject();
                node.put("brokerOrderId", o.brokerOrderId());
                if (o.clientRef() != null) node.put("clientRef", o.clientRef());
                node.put("symbol", o.symbol());
                node.put("side", o.side());
                node.put("qty", o.qty());
                node.put("type", o.type());
                node.put("status", o.status());
                node.put("role", o.role());
                if (o.filledQty() != null) node.put("filledQty", o.filledQty());
                if (o.avgFillPrice() != null) node.put("avgFillPrice", o.avgFillPrice());
                if (o.limitPrice() != null) node.put("limitPrice", o.limitPrice());
                if (o.stopPrice() != null) node.put("stopPrice", o.stopPrice());
                if (o.parentId() != null) node.put("parentId", o.parentId());
                if (o.submittedAt() != null) node.put("submittedAt", o.submittedAt());
                if (o.filledAt() != null) node.put("filledAt", o.filledAt());
            }
            out.put("asOf", java.time.Instant.now().toString());
            return ToolResult.ok(out);
        } catch (BrokerException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
