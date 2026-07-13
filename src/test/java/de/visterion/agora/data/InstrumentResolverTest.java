package de.visterion.agora.data;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.trading.saxo.SaxoDataAccess;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class InstrumentResolverTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }
    private final AtomicLong clock = new AtomicLong(0);

    private SaxoInstrumentResolver resolver(boolean withBearer) {
        SaxoDataAccess access = new SaxoDataAccess(
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                () -> withBearer ? Optional.of("Bearer t") : Optional.empty());
        return new SaxoInstrumentResolver(access, clock::get);
    }

    @Test void bareUsTickerReturnsRawWithoutHttp() {
        Instrument i = resolver(true).resolve("AAPL");
        assertThat(i.resolved()).isFalse();
        assertThat(i.displaySymbol()).isEqualTo("AAPL");
        wm.verify(0, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    @Test void dottedUnmappedReturnsRawWithoutHttp() {
        assertThat(resolver(true).resolve("XYZ.OL").resolved()).isFalse();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    @Test void noBearerReturnsRawWithoutHttp() {
        assertThat(resolver(false).resolve("SAP.DE").resolved()).isFalse();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/ref/v1/instruments")));
    }

    @Test void blankOrNullReturnsRaw() {
        assertThat(resolver(true).resolve("   ").resolved()).isFalse();
        assertThat(resolver(true).resolve(null).resolved()).isFalse();
    }
}
