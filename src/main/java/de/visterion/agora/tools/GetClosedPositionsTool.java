package de.visterion.agora.tools;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import de.visterion.agora.trading.BrokerException;
import de.visterion.agora.trading.BrokerService;
import de.visterion.agora.trading.ClosedPosition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GetClosedPositionsTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetClosedPositionsTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "get_closed_positions"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "List closed (already-settled) positions on the named connection, with real broker "
                + "fill prices/P&L — for reconciling a position that closed at the broker "
                + "(e.g. stopped out) between reconcile cycles.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("connection").put("type", "string")
                .put("description", "Target connection id (see list_connections)");
        props.putObject("from").put("type", "string")
                .put("description", "ISO-8601 UTC bound on close time; Saxo covers only its rolling window (windowLimited)");
        props.putObject("to").put("type", "string")
                .put("description", "ISO-8601 UTC bound on close time; Saxo covers only its rolling window (windowLimited)");
        schema.putArray("required").add("connection");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("connection"))
            return ToolResult.unavailable("missing required argument: connection");
        String connection = args.get("connection").asString();
        String from = args.hasNonNull("from") ? args.get("from").asString(null) : null;
        String to = args.hasNonNull("to") ? args.get("to").asString(null) : null;
        try {
            ObjectNode out = mapper.createObjectNode();
            if (!broker.supportsClosedPositions(connection)) {
                out.putArray("closedPositions");
                out.put("supported", false);
                out.put("note", "alpaca: no native closed-position source — use get_orders");
                return ToolResult.ok(out);
            }
            var closedPositions = broker.closedPositions(connection, from, to);
            var arr = out.putArray("closedPositions");
            for (ClosedPosition cp : closedPositions) {
                ObjectNode n = arr.addObject();
                n.put("symbol", cp.symbol());
                n.put("uic", cp.uic());
                n.put("openPrice", cp.openPrice());
                n.put("closePrice", cp.closePrice());
                n.put("amount", cp.amount());
                n.put("profitLoss", cp.profitLoss());
                if (cp.clientRef() != null) n.put("clientRef", cp.clientRef());
                if (cp.openTime() != null) n.put("openTime", cp.openTime());
                if (cp.closeTime() != null) n.put("closeTime", cp.closeTime());
                if (cp.openingPositionId() != null) n.put("openingPositionId", cp.openingPositionId());
            }
            if (from != null || to != null) {
                out.put("windowLimited", true);
                out.put("note", "saxo closed positions cover only the broker's current rolling window, "
                        + "not full history — from/to is a client-side filter over that window");
            }
            return ToolResult.ok(out);
        } catch (BrokerException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
