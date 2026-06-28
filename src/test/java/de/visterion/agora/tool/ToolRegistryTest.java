package de.visterion.agora.tool;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AgoraTool echoTool() {
        return new AgoraTool() {
            public String name() { return "echo"; }
            public String description() { return "echoes its input"; }
            public ObjectNode inputSchema() { return mapper.createObjectNode().put("type", "object"); }
            public ToolResult call(tools.jackson.databind.JsonNode args) {
                return ToolResult.ok(args);
            }
        };
    }

    @Test
    void listsRegisteredTools() {
        ToolRegistry reg = new ToolRegistry(List.of(echoTool()));
        assertThat(reg.names()).containsExactly("echo");
    }

    @Test
    void dispatchesByName() {
        ToolRegistry reg = new ToolRegistry(List.of(echoTool()));
        ObjectNode in = mapper.createObjectNode().put("hello", "world");
        ToolResult r = reg.invoke("echo", in);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("hello").asString()).isEqualTo("world");
    }

    @Test
    void unknownToolThrows() {
        ToolRegistry reg = new ToolRegistry(List.of(echoTool()));
        assertThatThrownBy(() -> reg.invoke("nope", mapper.createObjectNode()))
                .isInstanceOf(ToolNotFoundException.class);
    }

    @Test
    void runtimeExceptionInToolYieldsUnavailable() {
        AgoraTool boom = new AgoraTool() {
            public String name() { return "boom"; }
            public String description() { return "always throws"; }
            public ObjectNode inputSchema() { return mapper.createObjectNode(); }
            public ToolResult call(tools.jackson.databind.JsonNode args) {
                throw new RuntimeException("unexpected failure");
            }
        };
        ToolRegistry reg = new ToolRegistry(List.of(boom));
        ToolResult r = reg.invoke("boom", mapper.createObjectNode());
        assertThat(r.available()).isFalse();
    }

    @Test
    void preservesInsertionOrder() {
        AgoraTool alpha = new AgoraTool() {
            public String name() { return "alpha"; }
            public String description() { return "first"; }
            public ObjectNode inputSchema() { return mapper.createObjectNode(); }
            public ToolResult call(tools.jackson.databind.JsonNode args) { return ToolResult.ok(args); }
        };
        AgoraTool beta = new AgoraTool() {
            public String name() { return "beta"; }
            public String description() { return "second"; }
            public ObjectNode inputSchema() { return mapper.createObjectNode(); }
            public ToolResult call(tools.jackson.databind.JsonNode args) { return ToolResult.ok(args); }
        };
        AgoraTool gamma = new AgoraTool() {
            public String name() { return "gamma"; }
            public String description() { return "third"; }
            public ObjectNode inputSchema() { return mapper.createObjectNode(); }
            public ToolResult call(tools.jackson.databind.JsonNode args) { return ToolResult.ok(args); }
        };
        ToolRegistry reg = new ToolRegistry(List.of(alpha, beta, gamma));
        assertThat(reg.names()).containsExactly("alpha", "beta", "gamma");
    }
}
