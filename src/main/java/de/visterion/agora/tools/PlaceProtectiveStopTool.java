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

/**
 * Places a single protective stop order for an existing position. Purely additive — see
 * {@link de.visterion.agora.trading.BrokerProvider#placeProtectiveStop}: it cancels nothing
 * and reads no other order, so it is safe to call on a position whose protective state is
 * already messy (e.g. left partially covered by a failed rollback). The caller is responsible
 * for not double-covering shares another working stop already protects.
 */
@Component
public class PlaceProtectiveStopTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlaceProtectiveStopTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "place_protective_stop"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "Place ONE protective stop order for qty shares of an existing position at stopPrice, "
                + "on the named connection. Purely additive: cancels nothing and reads no other order — "
                + "safe to call on a position whose protective state is already messy. Rejects, before "
                + "any order is placed, when there is no open position, qty is not positive, or qty "
                + "exceeds the position size. The caller is responsible for not double-covering shares "
                + "another working stop already protects.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("connection").put("type", "string")
                .put("description", "Target connection id (see list_connections)");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol");
        props.putObject("qty").put("type", "number").put("description", "Quantity to protect (must not exceed the position size)");
        props.putObject("stop_price").put("type", "number").put("description", "Stop trigger price");
        schema.putArray("required").add("connection").add("symbol").add("qty").add("stop_price");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        String connection;
        String symbol;
        BigDecimal qty;
        BigDecimal stopPrice;
        try {
            connection = ToolParams.requiredString(args, "connection");
            symbol = ToolParams.requiredString(args, "symbol");
            qty = requiredDecimal(args, "qty");
            stopPrice = requiredDecimal(args, "stop_price");
        } catch (InvalidArgumentException e) {
            return ToolResult.unavailable(e.getMessage());
        }

        if (qty.signum() <= 0)
            return ToolResult.unavailable("qty must be positive");
        if (stopPrice.signum() <= 0)
            return ToolResult.unavailable("stop_price must be positive");

        try {
            OrderResult r = broker.placeProtectiveStop(connection, symbol, qty, stopPrice);
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

    /** Required decimal: presence-checked, then parsed via {@link ToolParams#optionalDecimal}
     *  so malformed values raise the same explicit "invalid numeric argument" error as optional fields. */
    private BigDecimal requiredDecimal(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field))
            throw new InvalidArgumentException("missing or blank argument: " + field);
        return ToolParams.optionalDecimal(args, field);
    }
}
