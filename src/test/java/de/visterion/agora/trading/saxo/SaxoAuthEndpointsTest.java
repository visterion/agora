package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SaxoAuthEndpointsTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(0);

    private static ConnectionConfig saxoCfg() {
        ConnectionConfig c = new ConnectionConfig();
        c.setProvider("saxo");
        c.setEnvironment(ConnectionConfig.Environment.PAPER);
        c.setKeyId("app-key");
        c.setSecret("app-secret");
        c.getExtra().put("redirect-uri", "http://localhost:8091/auth/saxo/callback");
        c.getExtra().put("auth-base-url", "http://auth.example");
        return c;
    }

    private ConnectionRegistry registry(ConnectionConfig cfg) {
        ConnectionsProperties props = new ConnectionsProperties();
        props.setConnections(Map.of("saxo-sim", cfg));
        BrokerProviderFactory f = new BrokerProviderFactory() {
            public String provider() { return "saxo"; }
            public BrokerProvider create(ConnectionConfig c) { return null; }
        };
        return new ConnectionRegistry(props, List.of(f));
    }

    private SaxoAuthEndpoints endpoints(SaxoOAuthClient oauth) {
        return new SaxoAuthEndpoints(registry(saxoCfg()),
                new SaxoTokenStores(dir, now::get), new SaxoAuthState(now::get), oauth);
    }

    @Test
    void loginRedirectsToAuthorizeWithStateAndClientId() {
        ResponseEntity<String> r = endpoints(mock(SaxoOAuthClient.class)).login("saxo-sim");
        assertThat(r.getStatusCode().value()).isEqualTo(302);
        String loc = r.getHeaders().getFirst("Location");
        assertThat(loc).startsWith("http://auth.example/authorize?")
                .contains("response_type=code")
                .contains("client_id=app-key")
                .contains("redirect_uri=http")
                .contains("state=");
    }

    @Test
    void loginUnknownConnectionIs404() {
        ResponseEntity<String> r = endpoints(mock(SaxoOAuthClient.class)).login("nope");
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(r.getBody()).doesNotContain("app-key");
    }

    @Test
    void callbackWithValidStateExchangesCodeAndStoresTokens() {
        SaxoOAuthClient oauth = mock(SaxoOAuthClient.class);
        when(oauth.exchangeCode(any(), eq("code-1")))
                .thenReturn(new SaxoOAuthClient.SaxoTokens("acc-1", 1200, "ref-1"));
        SaxoTokenStores stores = new SaxoTokenStores(dir, now::get);
        SaxoAuthState states = new SaxoAuthState(now::get);
        ConnectionRegistry registry = registry(saxoCfg());
        SaxoAuthEndpoints ep = new SaxoAuthEndpoints(registry, stores, states, oauth);

        String state = states.issue("saxo-sim");
        ResponseEntity<String> r = ep.callback("code-1", state);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("saxo-sim").doesNotContain("acc-1").doesNotContain("ref-1");
        assertThat(stores.forConnection("saxo-sim").validAccessToken()).contains("acc-1");
        assertThat(registry.get("saxo-sim").orElseThrow().probeStatus().state()).isEqualTo("ok");
    }

    @Test
    void callbackWithBadStateIs400WithoutDetails() {
        ResponseEntity<String> r = endpoints(mock(SaxoOAuthClient.class)).callback("code-1", "bogus");
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).isEqualTo("invalid state");
    }

    @Test
    void callbackExchangeFailureIs502WithoutDetails() {
        SaxoOAuthClient oauth = mock(SaxoOAuthClient.class);
        when(oauth.exchangeCode(any(), any())).thenThrow(new IllegalStateException("boom secret"));
        SaxoAuthState states = new SaxoAuthState(now::get);
        SaxoAuthEndpoints ep = new SaxoAuthEndpoints(registry(saxoCfg()),
                new SaxoTokenStores(dir, now::get), states, oauth);
        String state = states.issue("saxo-sim");

        ResponseEntity<String> r = ep.callback("code-1", state);

        assertThat(r.getStatusCode().value()).isEqualTo(502);
        assertThat(r.getBody()).isEqualTo("token exchange failed");
    }
}
