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

    // M-X7: an unexpected bug (e.g. NPE, no message) must map to a generic, non-null,
    // per-tool message — never a raw (possibly null) e.getMessage().
    @Test
    void unexpectedRuntimeExceptionYieldsGenericInternalErrorMessage() {
        AgoraTool npe = new AgoraTool() {
            public String name() { return "npe-tool"; }
            public String description() { return "throws NPE with no message"; }
            public ObjectNode inputSchema() { return mapper.createObjectNode(); }
            public ToolResult call(tools.jackson.databind.JsonNode args) {
                throw new NullPointerException();
            }
        };
        ToolRegistry reg = new ToolRegistry(List.of(npe));
        ToolResult r = reg.invoke("npe-tool", mapper.createObjectNode());
        assertThat(r.available()).isFalse();
        assertThat(r.error()).isEqualTo("internal error in tool 'npe-tool'");
    }

    // M-X7: ToolParams.InvalidArgumentException (caller-supplied-argument problem) must be
    // distinguished from an internal bug and its message safely echoed back.
    @Test
    void invalidArgumentExceptionYieldsInvalidArgumentMessage() {
        AgoraTool badArgs = new AgoraTool() {
            public String name() { return "bad-args"; }
            public String description() { return "rejects its args"; }
            public ObjectNode inputSchema() { return mapper.createObjectNode(); }
            public ToolResult call(tools.jackson.databind.JsonNode args) {
                throw new ToolParams.InvalidArgumentException("missing or blank argument: symbol");
            }
        };
        ToolRegistry reg = new ToolRegistry(List.of(badArgs));
        ToolResult r = reg.invoke("bad-args", mapper.createObjectNode());
        assertThat(r.available()).isFalse();
        assertThat(r.error()).isEqualTo("invalid argument: missing or blank argument: symbol");
    }

    @Test
    void illegalArgumentExceptionYieldsInvalidArgumentMessage() {
        AgoraTool badArgs = new AgoraTool() {
            public String name() { return "bad-args-2"; }
            public String description() { return "rejects its args"; }
            public ObjectNode inputSchema() { return mapper.createObjectNode(); }
            public ToolResult call(tools.jackson.databind.JsonNode args) {
                throw new IllegalArgumentException("days must be <= 1825");
            }
        };
        ToolRegistry reg = new ToolRegistry(List.of(badArgs));
        ToolResult r = reg.invoke("bad-args-2", mapper.createObjectNode());
        assertThat(r.available()).isFalse();
        assertThat(r.error()).isEqualTo("invalid argument: days must be <= 1825");
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
