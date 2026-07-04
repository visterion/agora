package de.visterion.agora.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test for the MCP Streamable-HTTP front-door: a real MCP sync
 * client connects to the running app at {@code /mcp}, lists tools, and calls
 * {@code ping} — proving the {@link McpToolAdapter} bridges the {@code ToolRegistry}
 * onto the Spring AI MCP server. Bearer auth stays enforced; the client injects the
 * {@code Authorization} header on every request via the transport's request
 * customizer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "agora.auth.tokens=test-token")
class McpStreamableHttpSmokeIT {

    @LocalServerPort
    int port;

    @Test
    void listsAndCallsPingOverMcp() {
        var transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                // Bearer auth guards /mcp too — inject the token on every MCP request.
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.setHeader("Authorization", "Bearer test-token"))
                .build();

        try (McpSyncClient client = McpClient.sync(transport).build()) {
            client.initialize();

            McpSchema.ListToolsResult tools = client.listTools();
            var toolNames = tools.tools().stream()
                    .map(McpSchema.Tool::name)
                    .toList();

            // General tools must be present
            assertThat(toolNames).contains("ping");
            assertThat(toolNames).contains(
                    "get_quote", "get_ohlc",
                    "get_intraday", "get_fx_rate",
                    "get_company_news", "get_fundamentals", "get_analyst_estimates", "get_earnings_calendar",
                    "get_filings", "get_eps_history", "get_index_constituents");

            // Slice 6 ta4j research tools must be present
            assertThat(toolNames).contains(
                    "get_rsi", "get_macd", "get_bollinger", "get_stochastic",
                    "get_adx", "get_obv", "get_cci", "get_williams_r", "get_r_framework");

            // Trading tools must NOT appear on the MCP endpoint (webhook-only)
            assertThat(toolNames).doesNotContain("place_bracket");

            McpSchema.CallToolResult res = client.callTool(
                    new McpSchema.CallToolRequest("ping", Map.of("message", "hi")));

            assertThat(res.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(res.content()).isNotEmpty();
            String text = ((McpSchema.TextContent) res.content().getFirst()).text();
            assertThat(text).contains("\"pong\":true").contains("\"message\":\"hi\"");
        }
    }
}
