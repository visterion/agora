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

import java.math.BigDecimal;

@Component
public class ModifyBracketTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public ModifyBracketTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "modify_bracket"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "Modify the stop-loss and/or take-profit levels of an existing bracket order on the named connection.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("connection").put("type", "string")
                .put("description", "Target connection id (see list_connections)");
        props.putObject("orderId").put("type", "string").put("description", "Bracket parent order ID (from place_bracket's orderId)");
        props.putObject("symbol").put("type", "string").put("description", "Instrument symbol of the bracket's position");
        props.putObject("stop").put("type", "number").put("description", "New stop-loss stop price");
        props.putObject("target").put("type", "number").put("description", "New take-profit limit price");
        schema.putArray("required").add("connection").add("orderId").add("symbol");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("connection"))
            return ToolResult.unavailable("missing required argument: connection");
        String connection = args.get("connection").asString();
        if (!args.hasNonNull("orderId"))
            return ToolResult.unavailable("missing required argument: orderId");

        String orderId = args.get("orderId").asString();
        BigDecimal stop = safeDecimal(args, "stop");
        BigDecimal target = safeDecimal(args, "target");

        if (stop == null && target == null)
            return ToolResult.unavailable("must provide at least one of: stop, target");

        if (!args.hasNonNull("symbol"))
            return ToolResult.unavailable("missing required argument: symbol");
        String symbol = args.get("symbol").asString();

        try {
            OrderResult r = broker.modifyBracket(connection, orderId, symbol, stop, target);
            return mapResult(r);
        } catch (BrokerException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }

    private BigDecimal safeDecimal(JsonNode args, String field) {
        try {
            return args.hasNonNull(field) ? args.get(field).decimalValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private ToolResult mapResult(OrderResult r) {
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
    }
}
