package de.visterion.agora.trading.saxo;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class SaxoDataAccessTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private SaxoDataAccess access(Supplier<Optional<String>> bearer) {
        return new SaxoDataAccess(RestClient.builder().baseUrl(wm.baseUrl()).build(), bearer);
    }

    private SaxoDataAccess access(Supplier<Optional<String>> bearer, String configuredAccountKey, java.util.function.LongSupplier now) {
        return new SaxoDataAccess(RestClient.builder().baseUrl(wm.baseUrl()).build(), bearer, configuredAccountKey, now);
    }

    @Test void bearerEmptyWhenNoToken() {
        assertThat(access(Optional::empty).bearer()).isEmpty();
    }

    @Test void bearerPresentWhenTokenValid() {
        assertThat(access(() -> Optional.of("Bearer abc")).bearer()).contains("Bearer abc");
    }

    @Test void disabledBridgeHasEmptyBearerAndNullHttp() {
        SaxoDataAccess a = new SaxoDataAccess(null, Optional::empty);
        assertThat(a.bearer()).isEmpty();
        assertThat(a.http()).isNull();
        assertThat(a.accountKey()).isEmpty();
    }

    @Test void accountKeyFetchedOnceAndCached() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/accounts/me")).willReturn(okJson("""
            {"Data":[{"AccountGroupKey":"g","AccountId":"123","AccountKey":"AbCdEf-KEY","AccountType":"Normal","Active":true}]}
            """)));
        SaxoDataAccess a = access(() -> Optional.of("Bearer abc"));
        assertThat(a.accountKey()).contains("AbCdEf-KEY");
        assertThat(a.accountKey()).contains("AbCdEf-KEY");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/port/v1/accounts/me")));
    }

    @Test void accountKeyEmptyOnHttpErrorAndNotCached() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/accounts/me")).willReturn(status(401)));
        SaxoDataAccess a = access(() -> Optional.of("Bearer abc"));
        assertThat(a.accountKey()).isEmpty();
        // next call retries (failure is not cached)
        assertThat(a.accountKey()).isEmpty();
        wm.verify(2, getRequestedFor(urlPathEqualTo("/port/v1/accounts/me")));
    }

    @Test void accountKeyEmptyWithoutBearer() {
        SaxoDataAccess a = access(Optional::empty);
        assertThat(a.accountKey()).isEmpty();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/port/v1/accounts/me")));
    }

    @Test void accountKeyPicksConfiguredAccountAmongMultiple() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/accounts/me")).willReturn(okJson("""
            {"Data":[{"AccountKey":"AAA-KEY","AccountType":"Normal"},
                     {"AccountKey":"BBB-KEY","AccountType":"Normal"}]}
            """)));
        SaxoDataAccess a = access(() -> Optional.of("Bearer abc"), "BBB-KEY", System::currentTimeMillis);
        assertThat(a.accountKey()).contains("BBB-KEY");
    }

    @Test void accountKeyFallsBackToDataZeroWithoutConfiguredKeyMatch() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/accounts/me")).willReturn(okJson("""
            {"Data":[{"AccountKey":"AAA-KEY","AccountType":"Normal"},
                     {"AccountKey":"BBB-KEY","AccountType":"Normal"}]}
            """)));
        SaxoDataAccess a = access(() -> Optional.of("Bearer abc"), null, System::currentTimeMillis);
        assertThat(a.accountKey()).contains("AAA-KEY");
    }

    @Test void accountKeyCacheExpiresAfterOneHourTtl() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/accounts/me")).willReturn(okJson("""
            {"Data":[{"AccountKey":"AAA-KEY","AccountType":"Normal"}]}
            """)));
        AtomicLong now = new AtomicLong(0L);
        SaxoDataAccess a = access(() -> Optional.of("Bearer abc"), null, now::get);

        assertThat(a.accountKey()).contains("AAA-KEY");
        assertThat(a.accountKey()).contains("AAA-KEY");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/port/v1/accounts/me")));   // still cached

        now.addAndGet(3_600_001L);   // past the 1h TTL
        assertThat(a.accountKey()).contains("AAA-KEY");
        wm.verify(2, getRequestedFor(urlPathEqualTo("/port/v1/accounts/me")));   // re-fetched
    }
}
