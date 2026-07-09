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
public class FlattenTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public FlattenTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "flatten"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "Close (flatten) a position for a given symbol via market order on the named connection. "
                + "By default closes the entire position; pass fraction or qty for a partial close.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("connection").put("type", "string")
                .put("description", "Target connection id (see list_connections)");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol to flatten");
        props.putObject("fraction").put("type", "number")
                .put("description", "Fraction of the position to close, 0 < fraction <= 1. Default: full close. Mutually exclusive with qty.");
        props.putObject("qty").put("type", "number")
                .put("description", "Exact quantity to close. Mutually exclusive with fraction.");
        schema.putArray("required").add("connection").add("symbol");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("connection"))
            return ToolResult.unavailable("missing required argument: connection");
        String connection = args.get("connection").asString();
        if (!args.hasNonNull("symbol"))
            return ToolResult.unavailable("missing required argument: symbol");

        String symbol = args.get("symbol").asString();

        BigDecimal fraction = safeDecimal(args, "fraction");
        BigDecimal qty = safeDecimal(args, "qty");

        if (fraction != null && qty != null)
            return ToolResult.unavailable("fraction and qty are mutually exclusive — provide at most one");
        if (fraction != null && (fraction.signum() <= 0 || fraction.compareTo(BigDecimal.ONE) > 0))
            return ToolResult.unavailable("fraction must be in (0, 1]");
        if (qty != null && qty.signum() <= 0)
            return ToolResult.unavailable("qty must be positive");

        try {
            OrderResult r = broker.flatten(connection, symbol, fraction, qty);
            ObjectNode out = mapper.createObjectNode();
            out.put("accepted", r.accepted());
            if (r.accepted()) {
                out.put("orderId", r.brokerOrderId());
                if (r.clientRef() != null) out.put("clientRef", r.clientRef());
                out.put("status", r.status());
                if (r.closedQty() != null) out.put("closedQty", r.closedQty());
                if (r.remainingQty() != null) out.put("remainingQty", r.remainingQty());
                if (r.avgFillPrice() != null) out.put("avgFillPrice", r.avgFillPrice());
            } else {
                out.put("rejectReason", r.rejectReason());
                out.put("rejectCode", r.rejectCode());
            }
            return ToolResult.ok(out);
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
}
