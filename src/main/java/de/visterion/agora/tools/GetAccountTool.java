package de.visterion.agora.tools;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import de.visterion.agora.trading.Account;
import de.visterion.agora.trading.BrokerException;
import de.visterion.agora.trading.BrokerService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GetAccountTool implements AgoraTool {

    private final BrokerService broker;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetAccountTool(BrokerService broker) { this.broker = broker; }

    @Override public String name() { return "get_account"; }
    @Override public String namespace() { return "trading"; }

    @Override
    public String description() {
        return "Retrieve account summary: equity, buying power, cash balance, and account status for the named connection.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("connection").put("type", "string")
                .put("description", "Target connection id (see list_connections)");
        schema.putArray("required").add("connection");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("connection"))
            return ToolResult.unavailable("missing required argument: connection");
        String connection = args.get("connection").asString();
        try {
            Account a = broker.account(connection);
            ObjectNode out = mapper.createObjectNode();
            ObjectNode acct = out.putObject("account");
            acct.put("accountId", a.accountId());
            acct.put("equity", a.equity());
            acct.put("buyingPower", a.buyingPower());
            acct.put("cash", a.cash());
            acct.put("currency", a.currency());
            acct.put("status", a.status());
            out.put("asOf", java.time.Instant.now().toString());
            return ToolResult.ok(out);
        } catch (BrokerException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
