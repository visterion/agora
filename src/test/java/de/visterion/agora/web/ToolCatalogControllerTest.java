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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ToolCatalogController.class)
@Import({ToolRegistry.class, PingTool.class, BearerTokenFilter.class})
@TestPropertySource(properties = {"agora.auth.tokens=good-token", "agora.trading.tokens="})
class ToolCatalogControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void listsToolsWithSchema() throws Exception {
        mvc.perform(get("/tools").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools[0].name").value("ping"))
                .andExpect(jsonPath("$.tools[0].inputSchema.type").value("object"));
    }

    @Test
    void rejectsWithoutBearer() throws Exception {
        mvc.perform(get("/tools"))
                .andExpect(status().isUnauthorized());
    }
}
