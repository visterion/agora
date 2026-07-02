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
        return "List all open and recent orders for the active broker account.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("status").put("type", "string")
                .put("description", "Filter by order status (e.g. open, closed, all). Optional.");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        String status = (args != null && args.hasNonNull("status"))
                ? args.get("status").asString(null) : null;
        try {
            List<Order> orders = broker.orders(status);
            ObjectNode out = mapper.createObjectNode();
            ArrayNode arr = out.putArray("orders");
            for (Order o : orders) {
                ObjectNode node = arr.addObject();
                node.put("brokerOrderId", o.brokerOrderId());
                node.put("clientRef", o.clientRef());
                node.put("symbol", o.symbol());
                node.put("side", o.side());
                node.put("qty", o.qty());
                node.put("type", o.type());
                node.put("status", o.status());
            }
            return ToolResult.ok(out);
        } catch (BrokerException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
