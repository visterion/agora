package de.visterion.agora.tools;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolParams;
import de.visterion.agora.tool.ToolParams.InvalidArgumentException;
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
        return "Modify the stop-loss and/or take-profit levels of an existing bracket order on the named "
                + "connection. By default the broker resolves which orders carry the legs, from the bracket "
                + "parent or from the symbol — which is only unambiguous while the symbol holds ONE bracket. "
                + "If the position was built in several tranches it has more than one protective stop working "
                + "on the same instrument: pass stopOrderId (and/or targetOrderId) to say exactly which leg to "
                + "move. When you name one leg you must name every leg you are re-pricing.";
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
        props.putObject("stopOrderId").put("type", "string").put("description",
                "Optional. Order id of the exact stop-loss leg to move (from place_bracket's stopLegId or "
                        + "get_orders). Required instead of guessing when the symbol carries more than one "
                        + "working stop, e.g. a position built in two tranches.");
        props.putObject("targetOrderId").put("type", "string").put("description",
                "Optional. Order id of the exact take-profit leg to move. If you pass either leg id, you "
                        + "must pass one for every leg you are re-pricing.");
        schema.putArray("required").add("connection").add("orderId").add("symbol");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        String connection;
        String orderId;
        BigDecimal stop;
        BigDecimal target;
        try {
            connection = ToolParams.requiredString(args, "connection");
            orderId = ToolParams.requiredString(args, "orderId");
            stop = ToolParams.optionalDecimal(args, "stop");
            target = ToolParams.optionalDecimal(args, "target");
        } catch (InvalidArgumentException e) {
            return ToolResult.unavailable(e.getMessage());
        }

        if (stop != null && stop.signum() <= 0)
            return ToolResult.unavailable("stop must be positive");
        if (target != null && target.signum() <= 0)
            return ToolResult.unavailable("target must be positive");

        if (stop == null && target == null)
            return ToolResult.unavailable("must provide at least one of: stop, target");

        String symbol;
        String stopOrderId;
        String targetOrderId;
        try {
            symbol = ToolParams.requiredString(args, "symbol");
            stopOrderId = ToolParams.optionalString(args, "stopOrderId");
            targetOrderId = ToolParams.optionalString(args, "targetOrderId");
        } catch (InvalidArgumentException e) {
            return ToolResult.unavailable(e.getMessage());
        }

        try {
            OrderResult r = broker.modifyBracket(connection, orderId, symbol, stop, target,
                    stopOrderId, targetOrderId);
            return mapResult(r);
        } catch (BrokerException e) {
            return ToolResult.unavailable(e.getMessage());
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
