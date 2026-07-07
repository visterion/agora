package de.visterion.agora.tools;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import de.visterion.agora.trading.BracketOrderRequest;
import de.visterion.agora.trading.BrokerException;
import de.visterion.agora.trading.BrokerService;
import de.visterion.agora.trading.OrderResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

@Component
public class PlaceBracketTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlaceBracketTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "place_bracket"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "Place a bracket order (entry + stop-loss + take-profit) on the named connection.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("connection").put("type", "string")
                .put("description", "Target connection id (see list_connections)");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol");
        props.putObject("side").put("type", "string").put("description", "buy or sell");
        props.putObject("qty").put("type", "number").put("description", "Quantity");
        props.putObject("type").put("type", "string").put("description", "Order type (default: limit)");
        props.putObject("timeInForce").put("type", "string").put("description", "Time in force (default: gtc)");
        props.putObject("limitPrice").put("type", "number").put("description", "Limit price (required for limit orders)");
        props.putObject("stopLossStop").put("type", "number").put("description", "Stop-loss stop price");
        props.putObject("stopLossLimit").put("type", "number").put("description", "Stop-loss limit price (optional)");
        props.putObject("takeProfitLimit").put("type", "number").put("description", "Take-profit limit price");
        props.putObject("clientRef").put("type", "string").put("description", "Client reference ID (optional)");
        schema.putArray("required").add("connection").add("symbol").add("side").add("qty").add("stopLossStop").add("takeProfitLimit");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("connection"))
            return ToolResult.unavailable("missing required argument: connection");
        String connection = args.get("connection").asString();
        if (!args.hasNonNull("symbol"))
            return ToolResult.unavailable("missing required argument: symbol");
        if (!args.hasNonNull("side"))
            return ToolResult.unavailable("missing required argument: side");
        if (!args.hasNonNull("qty"))
            return ToolResult.unavailable("missing required argument: qty");
        if (!args.hasNonNull("stopLossStop"))
            return ToolResult.unavailable("missing required argument: stopLossStop");
        if (!args.hasNonNull("takeProfitLimit"))
            return ToolResult.unavailable("missing required argument: takeProfitLimit");

        String symbol = args.get("symbol").asString();
        String side = args.get("side").asString();

        BigDecimal qty;
        BigDecimal stopLossStop;
        BigDecimal takeProfitLimit;
        try {
            qty = args.get("qty").decimalValue();
            stopLossStop = args.get("stopLossStop").decimalValue();
            takeProfitLimit = args.get("takeProfitLimit").decimalValue();
        } catch (Exception e) {
            return ToolResult.unavailable("invalid numeric argument: " + e.getMessage());
        }

        String type = args.hasNonNull("type") ? args.get("type").asString() : "limit";
        String timeInForce = args.hasNonNull("timeInForce") ? args.get("timeInForce").asString() : "gtc";
        BigDecimal limitPrice = safeDecimal(args, "limitPrice");
        BigDecimal stopLossLimit = safeDecimal(args, "stopLossLimit");
        String clientRef = args.hasNonNull("clientRef") ? args.get("clientRef").asString() : null;

        BracketOrderRequest req = new BracketOrderRequest(
                symbol, side, qty, type, timeInForce, limitPrice,
                stopLossStop, stopLossLimit, takeProfitLimit, clientRef);

        try {
            OrderResult r = broker.submitBracket(connection, req);
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
