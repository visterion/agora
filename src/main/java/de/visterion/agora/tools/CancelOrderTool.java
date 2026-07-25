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

@Component
public class CancelOrderTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public CancelOrderTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "cancel_order"; }
    @Override public String namespace() { return "trading"; }

    @Override public String description() {
        return "Cancel an open order by broker order id. An unknown order id is reported as a "
                + "result, not an outage: accepted=false with rejectCode NOT_FOUND (the order may "
                + "already be cancelled, expired or filled) -- do not retry it.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("connection").put("type", "string")
                .put("description", "Target connection id (see list_connections)");
        props.putObject("orderId").put("type", "string").put("description", "Broker order ID to cancel");
        schema.putArray("required").add("connection").add("orderId");
        return schema;
    }

    @Override public ToolResult call(JsonNode args) {
        String connection;
        String orderId;
        try {
            connection = ToolParams.requiredString(args, "connection");
            orderId = ToolParams.requiredString(args, "orderId");
        } catch (InvalidArgumentException e) {
            return ToolResult.unavailable(e.getMessage());
        }
        try {
            OrderResult r = broker.cancel(connection, orderId);
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
            if (e.kind() == BrokerException.Kind.NOT_FOUND) {
                // An order id the broker does not know is a DEFINITE answer, not an outage:
                // reported as unavailable it looks like "broker down", which invites the caller
                // into a retry loop that can never succeed.
                //
                // But unlike get_order_by_ref this is deliberately NOT flattened into an
                // idempotent success. At the broker, "not found" is ambiguous:
                //   (a) the order is already cancelled/expired -- the caller's intent is met, or
                //   (b) the order FILLED and left the working book -- a live position exists, or
                //   (c) the id was simply wrong.
                // Nothing in the DELETE response tells these apart. Dracul's consumer
                // (AgoraExecutionGateway.cancelOrder -> EntryExpiryService.cancelFully) is void
                // and reads any non-throwing return as "cancelled", flipping the book row to
                // CANCELLED. Doing that for case (b) would orphan a real, unguarded position --
                // a direct breach of the always-guarded principle. A cancel that wrongly claims
                // success is more dangerous than one that wrongly reports a problem.
                //
                // So we report the conservative, DISTINGUISHABLE outcome: a business rejection
                // (available=true, accepted=false) carrying rejectCode=NOT_FOUND. Callers that
                // can resolve the ambiguity (e.g. by reconciling the order/position state) can
                // branch on that code; callers that cannot keep the safe old behaviour, since
                // accepted=false already means "did not happen" rather than "retry me".
                ObjectNode out = mapper.createObjectNode();
                out.put("accepted", false);
                out.put("orderId", orderId);
                out.put("rejectReason", e.getMessage());
                out.put("rejectCode", "NOT_FOUND");
                return ToolResult.ok(out);
            }
            // UNAVAILABLE / NOT_READY are real outages and stay retriable.
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
