package de.visterion.agora;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "agora.auth.tokens=test-token")
class AgoraApplicationTests {
    @Test
    void contextLoads() {
    }
}
