package de.visterion.agora.mcp;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolRegistry;
import de.visterion.agora.tool.ToolResult;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Bridges every {@link AgoraTool} in the {@link ToolRegistry} into a programmatic
 * MCP {@link SyncToolSpecification}. Spring AI's MCP server auto-config injects this
 * {@code List<SyncToolSpecification>} bean (via
 * {@code McpServerAutoConfiguration#mcpSyncServer}, which takes an
 * {@code ObjectProvider<List<SyncToolSpecification>>}) and exposes the specs over the
 * Streamable-HTTP endpoint at {@code /mcp}.
 *
 * <p>This is the SAME tool truth the webhook controller dispatches to — one registry,
 * two front-doors. Adding an {@code AgoraTool} bean automatically surfaces it on both.
 */
@Configuration
public class McpToolAdapter {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = new ObjectMapper();

    @Bean
    public List<SyncToolSpecification> agoraMcpTools(ToolRegistry registry) {
        return registry.all().stream().map(tool -> toSpec(tool, registry)).toList();
    }

    private SyncToolSpecification toSpec(AgoraTool tool, ToolRegistry registry) {
        // The SDK's Tool.builder accepts the input JSON Schema as a Map<String,Object>.
        Map<String, Object> schema = mapper.convertValue(tool.inputSchema(), MAP_TYPE);

        McpSchema.Tool def = McpSchema.Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(schema)
                .build();

        return SyncToolSpecification.builder()
                .tool(def)
                .callHandler((exchange, request) -> handle(tool.name(), registry, request))
                .build();
    }

    private McpSchema.CallToolResult handle(String toolName, ToolRegistry registry, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        JsonNode argsNode = mapper.valueToTree(args);

        ToolResult result = registry.invoke(toolName, argsNode);

        Object payload = result.available()
                ? result.output()
                : Map.of("available", false, "error", result.error());
        String json = mapper.writeValueAsString(payload);

        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .isError(!result.available())
                .build();
    }
}
