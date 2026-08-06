package de.visterion.agora.mcp;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolRegistry;
import de.visterion.agora.tool.ToolResult;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire consequence of {@link ToolResult#noData}: {@code isError} is the sole outage
 * discriminator consumers have, so a "this ONE item has nothing" answer must not set it while a
 * genuine source outage still must.
 */
class McpToolAdapterErrorEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimal stub tool: returns whatever result it was constructed with. */
    private record StubTool(String name, ToolResult result) implements AgoraTool {
        public String description() { return "stub"; }
        public ObjectNode inputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
        public ToolResult call(JsonNode args) { return result; }
    }

    private McpSchema.CallToolResult invoke(ToolResult result) {
        AgoraTool tool = new StubTool("stub_tool", result);
        List<SyncToolSpecification> specs =
                new McpToolAdapter().agoraMcpTools(new ToolRegistry(List.of(tool)));
        assertThat(specs).hasSize(1);
        return specs.getFirst().callHandler()
                .apply(null, new McpSchema.CallToolRequest("stub_tool", Map.of()));
    }

    private static JsonNode payloadOf(McpSchema.CallToolResult res) {
        return MAPPER.readTree(((McpSchema.TextContent) res.content().getFirst()).text());
    }

    @Test
    void noDataIsNotAnErrorEnvelope() {
        ObjectNode payload = MAPPER.createObjectNode().put("symbol", "SYNA");
        payload.putArray("bars");
        McpSchema.CallToolResult res = invoke(ToolResult.noData(payload, "no intraday bars for SYNA"));

        assertThat(res.isError()).isFalse();
        JsonNode body = payloadOf(res);
        assertThat(body.get("symbol").asString()).isEqualTo("SYNA");
        assertThat(body.get("available").asBoolean()).isFalse();
        assertThat(body.get("error").asString()).isEqualTo("no intraday bars for SYNA");
    }

    @Test
    void unavailableStillIsAnErrorEnvelope() {
        McpSchema.CallToolResult res = invoke(ToolResult.unavailable("Yahoo intraday unreachable"));

        assertThat(res.isError()).isTrue();
        JsonNode body = payloadOf(res);
        assertThat(body.get("available").asBoolean()).isFalse();
        assertThat(body.get("error").asString()).isEqualTo("Yahoo intraday unreachable");
    }
}
