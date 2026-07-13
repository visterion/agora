package de.visterion.agora.tool;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ToolRegistryBootTest {
    @Autowired ToolRegistry registry;
    @Test void bootsWithGlobalFundamentalsTool() {
        assertThat(registry).isNotNull(); // context loaded == no Duplicate tool name throw
    }
}
