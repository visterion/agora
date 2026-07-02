package de.visterion.agora.web;

import de.visterion.agora.security.BearerTokenFilter;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import de.visterion.agora.tools.PingTool;
import de.visterion.agora.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ToolCatalogController.class)
@Import({ToolRegistry.class, PingTool.class, BearerTokenFilter.class,
        ToolCatalogControllerTest.StubTradingTool.class})
@TestPropertySource(properties = {"agora.auth.tokens=good-token", "agora.trading.tokens=trade-token"})
class ToolCatalogControllerTest {

    @Autowired MockMvc mvc;

    /** Stub trading-namespace tool registered to verify catalog filtering. */
    @Component
    static class StubTradingTool implements AgoraTool {
        private final ObjectMapper mapper = new ObjectMapper();
        @Override public String name() { return "stub_trading_tool"; }
        @Override public String namespace() { return "trading"; }
        @Override public String description() { return "Trading stub — must not appear in /tools"; }
        @Override public ObjectNode inputSchema() {
            ObjectNode s = mapper.createObjectNode(); s.put("type","object"); return s;
        }
        @Override public ToolResult call(JsonNode args) { return ToolResult.unavailable("stub"); }
    }

    @Test
    void listsToolsWithSchema() throws Exception {
        mvc.perform(get("/tools").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools[0].name").value("ping"))
                .andExpect(jsonPath("$.tools[0].inputSchema.type").value("object"));
    }

    @Test
    void catalogExcludesTradingNamespaceTools() throws Exception {
        mvc.perform(get("/tools").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                // ping (general) is present
                .andExpect(jsonPath("$.tools[?(@.name == 'ping')]").exists())
                // stub_trading_tool (trading) must be absent
                .andExpect(jsonPath("$.tools[?(@.name == 'stub_trading_tool')]").doesNotExist());
    }

    @Test
    void rejectsWithoutBearer() throws Exception {
        mvc.perform(get("/tools"))
                .andExpect(status().isUnauthorized());
    }
}
