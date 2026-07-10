package de.visterion.agora.tools;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolParams;
import de.visterion.agora.tool.ToolParams.InvalidArgumentException;
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
import java.util.ArrayList;
import java.util.List;

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
        String connection;
        String symbol;
        String side;
        BigDecimal qty;
        BigDecimal stopLossStop;
        BigDecimal takeProfitLimit;
        BigDecimal limitPrice;
        BigDecimal stopLossLimit;
        String type;
        String timeInForce;
        String clientRef;
        try {
            connection = ToolParams.requiredString(args, "connection");
            symbol = ToolParams.requiredString(args, "symbol");
            side = ToolParams.requiredString(args, "side");
            qty = requiredDecimal(args, "qty");
            stopLossStop = requiredDecimal(args, "stopLossStop");
            takeProfitLimit = requiredDecimal(args, "takeProfitLimit");
            limitPrice = ToolParams.optionalDecimal(args, "limitPrice");
            stopLossLimit = ToolParams.optionalDecimal(args, "stopLossLimit");
            type = args.hasNonNull("type") ? args.get("type").asString() : "limit";
            timeInForce = args.hasNonNull("timeInForce") ? args.get("timeInForce").asString() : "gtc";
            clientRef = args.hasNonNull("clientRef") ? args.get("clientRef").asString() : null;
        } catch (InvalidArgumentException e) {
            return ToolResult.unavailable(e.getMessage());
        }

        List<String> violations = validate(side, qty, limitPrice, stopLossStop, takeProfitLimit, type);
        if (!violations.isEmpty())
            return ToolResult.unavailable(String.join("; ", violations));

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

    /** Required decimal: presence-checked, then parsed via {@link ToolParams#optionalDecimal}
     *  so malformed values raise the same explicit "invalid numeric argument" error as optional fields. */
    private BigDecimal requiredDecimal(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field))
            throw new InvalidArgumentException("missing or blank argument: " + field);
        return ToolParams.optionalDecimal(args, field);
    }

    /** M-X1/M-X3: side/sign/relational validation. Collects every violation instead of
     *  failing on the first, so the caller sees the full picture in one round-trip. */
    private List<String> validate(String side, BigDecimal qty, BigDecimal limitPrice,
                                   BigDecimal stopLossStop, BigDecimal takeProfitLimit, String type) {
        List<String> errors = new ArrayList<>();
        boolean buy = "buy".equals(side);
        boolean sell = "sell".equals(side);
        if (!buy && !sell)
            errors.add("side must be 'buy' or 'sell', got: " + side);

        if (qty.signum() <= 0) errors.add("qty must be > 0");
        if (stopLossStop.signum() <= 0) errors.add("stopLossStop must be > 0");
        if (takeProfitLimit.signum() <= 0) errors.add("takeProfitLimit must be > 0");
        if (limitPrice != null && limitPrice.signum() <= 0) errors.add("limitPrice must be > 0");

        boolean limitTypeMissingPrice = "limit".equals(type) && limitPrice == null;
        if (limitTypeMissingPrice) errors.add("limitPrice is required when type is limit");

        // Relational checks only make sense once side/signs/required-price are sane.
        if ((buy || sell) && !limitTypeMissingPrice
                && qty.signum() > 0 && stopLossStop.signum() > 0 && takeProfitLimit.signum() > 0
                && (limitPrice == null || limitPrice.signum() > 0)) {
            if (limitPrice != null) {
                if (buy && !(takeProfitLimit.compareTo(limitPrice) > 0 && limitPrice.compareTo(stopLossStop) > 0))
                    errors.add("buy requires takeProfitLimit > limitPrice > stopLossStop (got takeProfitLimit="
                            + takeProfitLimit + ", limitPrice=" + limitPrice + ", stopLossStop=" + stopLossStop + ")");
                if (sell && !(takeProfitLimit.compareTo(limitPrice) < 0 && limitPrice.compareTo(stopLossStop) < 0))
                    errors.add("sell requires takeProfitLimit < limitPrice < stopLossStop (got takeProfitLimit="
                            + takeProfitLimit + ", limitPrice=" + limitPrice + ", stopLossStop=" + stopLossStop + ")");
            } else {
                if (buy && takeProfitLimit.compareTo(stopLossStop) <= 0)
                    errors.add("buy requires takeProfitLimit > stopLossStop (got takeProfitLimit="
                            + takeProfitLimit + ", stopLossStop=" + stopLossStop + ")");
                if (sell && takeProfitLimit.compareTo(stopLossStop) >= 0)
                    errors.add("sell requires takeProfitLimit < stopLossStop (got takeProfitLimit="
                            + takeProfitLimit + ", stopLossStop=" + stopLossStop + ")");
            }
        }
        return errors;
    }

    private ToolResult mapResult(OrderResult r) {
        ObjectNode out = mapper.createObjectNode();
        out.put("accepted", r.accepted());
        if (r.accepted()) {
            out.put("orderId", r.brokerOrderId());
            if (r.clientRef() != null) out.put("clientRef", r.clientRef());
            out.put("status", r.status());
            if (r.stopLegId() != null) out.put("stopLegId", r.stopLegId());
            if (r.takeProfitLegId() != null) out.put("takeProfitLegId", r.takeProfitLegId());
        } else {
            out.put("rejectReason", r.rejectReason());
            out.put("rejectCode", r.rejectCode());
        }
        return ToolResult.ok(out);
    }
}
