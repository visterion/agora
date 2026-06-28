package de.visterion.agora.web;

import de.visterion.agora.security.BearerTokenFilter;
import de.visterion.agora.tools.PingTool;
import de.visterion.agora.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ToolWebhookController.class)
@Import({ToolRegistry.class, PingTool.class, BearerTokenFilter.class})
@TestPropertySource(properties = "agora.auth.tokens=good-token")
class ToolWebhookControllerTest {

    @Autowired MockMvc mvc;

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
}
