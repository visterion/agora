package de.visterion.agora.web;

import de.visterion.agora.security.BearerTokenFilter;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolRegistry;
import de.visterion.agora.tool.ToolResult;
import de.visterion.agora.tools.PingTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ToolWebhookController.class)
@Import({ToolRegistry.class, PingTool.class, BearerTokenFilter.class, ToolWebhookControllerTest.ExtraTools.class})
@TestPropertySource(properties = {"agora.auth.tokens=good-token", "agora.trading.tokens="})
class ToolWebhookControllerTest {

    @Autowired MockMvc mvc;

    @TestConfiguration
    static class ExtraTools {
        @Bean
        AgoraTool flakyTool() {
            return new AgoraTool() {
                private final ObjectMapper mapper = new ObjectMapper();

                @Override public String name() { return "flaky"; }
                @Override public String description() { return "Always unavailable"; }
                @Override public ObjectNode inputSchema() { return mapper.createObjectNode(); }
                @Override public ToolResult call(JsonNode args) { return ToolResult.unavailable("boom"); }
            };
        }
    }

    @Test
    void callsToolWithBearer() throws Exception {
        mvc.perform(post("/tools/ping")
                        .header("Authorization", "Bearer good-token")
                        .contentType("application/json")
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.pong").value(true))
                .andExpect(jsonPath("$.output.message").value("hi"));
    }

    @Test
    void rejectsWithoutBearer() throws Exception {
        mvc.perform(post("/tools/ping").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownToolIs404() throws Exception {
        mvc.perform(post("/tools/nope")
                        .header("Authorization", "Bearer good-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unavailableToolReturnsUnavailableEnvelope() throws Exception {
        mvc.perform(post("/tools/flaky")
                        .header("Authorization", "Bearer good-token")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.available").value(false))
                .andExpect(jsonPath("$.output.error").value("boom"));
    }
}
