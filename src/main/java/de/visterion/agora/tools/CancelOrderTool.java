package de.visterion.agora.tools;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import de.visterion.agora.trading.BrokerException;
import de.visterion.agora.trading.BrokerService;
import de.visterion.agora.trading.OrderResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class CancelOrderTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public CancelOrderTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "cancel_order"; }
    @Override public String namespace() { return "trading"; }

    @Override public String description() {
        return "Cancel an open order by broker order id.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("orderId").put("type", "string").put("description", "Broker order ID to cancel");
        schema.putArray("required").add("orderId");
        return schema;
    }

    @Override public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("orderId"))
            return ToolResult.unavailable("missing required argument: orderId");
        String orderId = args.get("orderId").asString();
        try {
            OrderResult r = broker.cancel(orderId);
            ObjectNode out = mapper.createObjectNode();
            out.put("accepted", r.accepted());
            if (r.accepted()) {
                out.put("orderId", r.brokerOrderId());
                out.put("status", r.status());
            } else {
                out.put("rejectReason", r.rejectReason());
                out.put("rejectCode", r.rejectCode());
            }
            return ToolResult.ok(out);
        } catch (BrokerException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
