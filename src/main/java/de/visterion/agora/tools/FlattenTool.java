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
public class FlattenTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public FlattenTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "flatten"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "Close (flatten) the entire position for a given symbol via market order.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol to flatten");
        schema.putArray("required").add("symbol");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("symbol"))
            return ToolResult.unavailable("missing required argument: symbol");

        String symbol = args.get("symbol").asString();

        try {
            OrderResult r = broker.flatten(symbol);
            ObjectNode out = mapper.createObjectNode();
            out.put("accepted", r.accepted());
            if (r.accepted()) {
                out.put("orderId", r.brokerOrderId());
                if (r.clientRef() != null) out.put("clientRef", r.clientRef());
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
