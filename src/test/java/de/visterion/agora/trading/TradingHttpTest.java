package de.visterion.agora.trading;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class TradingHttpTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    @Test
    void responseSlowerThanTimeoutFailsFast() {
        wm.stubFor(get(urlEqualTo("/slow")).willReturn(okJson("{}").withFixedDelay(3_000)));
        RestClient client = RestClient.builder()
                .requestFactory(TradingHttp.requestFactory(250L))
                .baseUrl(wm.baseUrl())
                .build();
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/slow").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
        assertThat((System.nanoTime() - t0) / 1_000_000L).isLessThan(2_500L);
    }
}
