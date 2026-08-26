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
public class FlattenTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public FlattenTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "flatten"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "Close (flatten) a position for a given symbol via market order on the named connection. "
                + "By default closes the entire position; pass fraction or qty for a partial close. "
                + "A partial close restores the protective orders sized to the remainder and returns "
                + "their new ids under protective_legs, keyed by the id each replaces. No open "
                + "position for the symbol is reported as a result, not an outage: accepted=false "
                + "with rejectCode NOT_FOUND -- do not retry it.";
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
        String connection;
        String symbol;
        BigDecimal fraction;
        BigDecimal qty;
        try {
            connection = ToolParams.requiredString(args, "connection");
            symbol = ToolParams.requiredString(args, "symbol");
            fraction = ToolParams.optionalDecimal(args, "fraction");
            qty = ToolParams.optionalDecimal(args, "qty");
        } catch (InvalidArgumentException e) {
            return ToolResult.unavailable(e.getMessage());
        }

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
            if (!r.protectiveLegs().isEmpty()) {
                var arr = out.putArray("protective_legs");
                for (var leg : r.protectiveLegs()) {
                    ObjectNode n = arr.addObject();
                    n.put("replaces", leg.replaces());
                    n.put("order_id", leg.orderId());
                    n.put("qty", leg.qty());
                    n.put("price", leg.price());
                }
                out.put("legs_collapsed", r.legsCollapsed());
            }
            return ToolResult.ok(out);
        } catch (BrokerException e) {
            if (e.kind() == BrokerException.Kind.NOT_FOUND) {
                // No open position for the symbol is a DEFINITE answer, not an outage: no retry
                // will ever make a gone position come back. Reported as unavailable it looks
                // like "broker down", which invites the caller into a retry loop that can never
                // succeed -- and worse, callers that branch on the shape of a rejection (rather
                // than treating every failure alike) never see the distinction at all.
                //
                // Unlike cancel_order's NOT_FOUND, this one is not ambiguous: a flatten found no
                // open position, full stop -- there is no "already cancelled vs filled vs wrong
                // id" split to preserve. So the safe, DISTINGUISHABLE outcome is a business
                // rejection (available=true, accepted=false) carrying rejectCode=NOT_FOUND.
                // Callers that can act on that (skip the retry, escalate as a reconciliation gap
                // rather than a transport failure) may branch on it; callers that cannot keep the
                // old behaviour, since accepted=false already means "did not happen".
                ObjectNode out = mapper.createObjectNode();
                out.put("accepted", false);
                out.put("rejectReason", e.getMessage());
                out.put("rejectCode", "NOT_FOUND");
                return ToolResult.ok(out);
            }
            // UNAVAILABLE / NOT_READY are real outages and stay retriable.
            return ToolResult.unavailable(e.getMessage());
        }
    }

}
