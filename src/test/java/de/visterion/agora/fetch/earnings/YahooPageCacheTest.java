package de.visterion.agora.fetch.earnings;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class YahooPageCacheTest {

    private WireMockServer server;
    private final AtomicLong clock = new AtomicLong(0L);
    private static final LocalDate FROM = LocalDate.parse("2026-07-27");
    private static final LocalDate TO = LocalDate.parse("2026-08-27");

    private static final String PAGE = """
            {"rows":[{"ticker":"ZZTOP","startdatetime":"2026-08-01T12:00:00.000Z",
                      "epsestimate":"1.25","epsactual":null,"epssurprisepct":null}]}""";

    @BeforeEach void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        server.stubFor(get(urlPathEqualTo("/v1/finance/calendar/earnings"))
                .willReturn(okJson(PAGE)));
    }

    @AfterEach void stop() { server.stop(); }

    private YahooEarningsProvider provider() {
        return new YahooEarningsProvider("http://localhost:" + server.port(),
                "agora-test", 2000L, 3600L, clock::get);
    }

    @Test void firstCallMissesAndReturnsEmptyWithoutBlocking() {
        var p = provider();
        assertThat(p.window(FROM, TO)).isEmpty();
    }

    @Test void warmPopulatesTheCacheAndSubsequentCallsHitIt() {
        var p = provider();
        p.window(FROM, TO);                                   // triggers the warm

        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> p.window(FROM, TO).isPresent());

        int callsAfterWarm = server.getAllServeEvents().size();
        p.window(FROM, TO);
        p.window(FROM, TO);
        assertThat(server.getAllServeEvents()).hasSize(callsAfterWarm);   // served from cache
    }

    @Test void aSecondWindowIsCachedSeparately() {
        var p = provider();
        p.window(FROM, TO);
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> p.window(FROM, TO).isPresent());

        assertThat(p.window(FROM, TO.plusDays(1))).isEmpty();   // different key -> miss
    }
}
