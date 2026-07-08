package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SaxoTokenRefresherTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000L);

    private static ConnectionConfig saxoCfg() {
        ConnectionConfig c = new ConnectionConfig();
        c.setProvider("saxo");
        c.setEnvironment(ConnectionConfig.Environment.PAPER);
        c.setKeyId("k"); c.setSecret("s");
        return c;
    }

    private ConnectionRegistry registry() {
        ConnectionsProperties props = new ConnectionsProperties();
        props.setConnections(Map.of("saxo-sim", saxoCfg()));
        BrokerProviderFactory f = new BrokerProviderFactory() {
            public String provider() { return "saxo"; }
            public BrokerProvider create(ConnectionConfig c) { return null; }
        };
        return new ConnectionRegistry(props, List.of(f));
    }

    @Test
    void refreshesWhenNoValidAccessTokenButRefreshPresent() {
        SaxoTokenStores stores = new SaxoTokenStores(dir, now::get);
        SaxoTokenStore store = stores.forConnection("saxo-sim");
        store.update("acc-0", 1200, "ref-0");
        now.addAndGet(1_300_000L);                                  // access expired
        SaxoOAuthClient oauth = mock(SaxoOAuthClient.class);
        when(oauth.refresh(any(), eq("ref-0")))
                .thenReturn(new SaxoOAuthClient.SaxoTokens("acc-1", 1200, "ref-1"));

        new SaxoTokenRefresher(registry(), stores, oauth).tick();

        assertThat(store.validAccessToken()).contains("acc-1");
        assertThat(store.refreshToken()).isEqualTo("ref-1");        // rolled
    }

    @Test
    void refreshesEarlyAtOneThirdRemaining() {
        SaxoTokenStores stores = new SaxoTokenStores(dir, now::get);
        SaxoTokenStore store = stores.forConnection("saxo-sim");
        store.update("acc-0", 1200, "ref-0");
        now.addAndGet(900_000L);                                    // 300s of 1200s left (< 1/3)
        SaxoOAuthClient oauth = mock(SaxoOAuthClient.class);
        when(oauth.refresh(any(), any()))
                .thenReturn(new SaxoOAuthClient.SaxoTokens("acc-1", 1200, "ref-1"));

        new SaxoTokenRefresher(registry(), stores, oauth).tick();

        verify(oauth).refresh(any(), eq("ref-0"));
    }

    @Test
    void freshTokenIsLeftAlone() {
        SaxoTokenStores stores = new SaxoTokenStores(dir, now::get);
        stores.forConnection("saxo-sim").update("acc-0", 1200, "ref-0");
        SaxoOAuthClient oauth = mock(SaxoOAuthClient.class);

        new SaxoTokenRefresher(registry(), stores, oauth).tick();   // 100% remaining

        verifyNoInteractions(oauth);
    }

    @Test
    void invalidGrantMarksDeadAndStopsRetrying() {
        SaxoTokenStores stores = new SaxoTokenStores(dir, now::get);
        SaxoTokenStore store = stores.forConnection("saxo-sim");
        store.update("acc-0", 1200, "ref-0");
        now.addAndGet(1_300_000L);
        SaxoOAuthClient oauth = mock(SaxoOAuthClient.class);
        when(oauth.refresh(any(), any()))
                .thenThrow(new SaxoOAuthClient.InvalidGrantException("rejected"));

        SaxoTokenRefresher r = new SaxoTokenRefresher(registry(), stores, oauth);
        r.tick();
        assertThat(store.dead()).isTrue();
        r.tick();                                                   // second tick: no more calls
        verify(oauth, times(1)).refresh(any(), any());
    }

    @Test
    void successfulRefreshSetsProbeStatusOk() {
        SaxoTokenStores stores = new SaxoTokenStores(dir, now::get);
        SaxoTokenStore store = stores.forConnection("saxo-sim");
        store.update("acc-0", 1200, "ref-0");
        now.addAndGet(1_300_000L);
        SaxoOAuthClient oauth = mock(SaxoOAuthClient.class);
        when(oauth.refresh(any(), eq("ref-0")))
                .thenReturn(new SaxoOAuthClient.SaxoTokens("acc-1", 1200, "ref-1"));
        ConnectionRegistry registry = registry();

        new SaxoTokenRefresher(registry, stores, oauth).tick();

        assertThat(registry.get("saxo-sim").orElseThrow().probeStatus().state()).isEqualTo("ok");
    }

    @Test
    void invalidGrantSetsProbeStatusUnreachable() {
        SaxoTokenStores stores = new SaxoTokenStores(dir, now::get);
        SaxoTokenStore store = stores.forConnection("saxo-sim");
        store.update("acc-0", 1200, "ref-0");
        now.addAndGet(1_300_000L);
        SaxoOAuthClient oauth = mock(SaxoOAuthClient.class);
        when(oauth.refresh(any(), any()))
                .thenThrow(new SaxoOAuthClient.InvalidGrantException("rejected"));
        ConnectionRegistry registry = registry();

        new SaxoTokenRefresher(registry, stores, oauth).tick();

        assertThat(registry.get("saxo-sim").orElseThrow().probeStatus().state()).isEqualTo("unreachable");
    }

    @Test
    void transientFailureRetriesNextTick() {
        SaxoTokenStores stores = new SaxoTokenStores(dir, now::get);
        SaxoTokenStore store = stores.forConnection("saxo-sim");
        store.update("acc-0", 1200, "ref-0");
        now.addAndGet(1_300_000L);
        SaxoOAuthClient oauth = mock(SaxoOAuthClient.class);
        when(oauth.refresh(any(), any()))
                .thenThrow(new IllegalStateException("HTTP 500"))
                .thenReturn(new SaxoOAuthClient.SaxoTokens("acc-1", 1200, "ref-1"));

        SaxoTokenRefresher r = new SaxoTokenRefresher(registry(), stores, oauth);
        r.tick();                                                   // fails, survives
        assertThat(store.dead()).isFalse();
        r.tick();                                                   // succeeds
        assertThat(store.validAccessToken()).contains("acc-1");
    }

    @Test
    void nonSaxoConnectionsAreIgnored() {
        ConnectionConfig alpaca = new ConnectionConfig();
        alpaca.setProvider("stub");
        alpaca.setEnvironment(ConnectionConfig.Environment.PAPER);
        alpaca.setKeyId("k"); alpaca.setSecret("s");
        ConnectionsProperties props = new ConnectionsProperties();
        props.setConnections(Map.of("other", alpaca));
        BrokerProviderFactory f = new BrokerProviderFactory() {
            public String provider() { return "stub"; }
            public BrokerProvider create(ConnectionConfig c) { return null; }
        };
        SaxoOAuthClient oauth = mock(SaxoOAuthClient.class);

        new SaxoTokenRefresher(new ConnectionRegistry(props, List.of(f)),
                new SaxoTokenStores(dir, now::get), oauth).tick();

        verifyNoInteractions(oauth);
    }

    @Test
    void warmUpRefreshesAtBootWhenAccessMissing() {
        SaxoTokenStores stores = new SaxoTokenStores(dir, now::get);
        SaxoTokenStore store = stores.forConnection("saxo-sim");
        store.update("acc-0", 1200, "ref-0");
        now.addAndGet(1_300_000L);                 // no valid access, refresh present
        SaxoOAuthClient oauth = mock(SaxoOAuthClient.class);
        when(oauth.refresh(any(), eq("ref-0")))
                .thenReturn(new SaxoOAuthClient.SaxoTokens("acc-1", 1200, "ref-1"));

        new SaxoTokenRefresher(registry(), stores, oauth).warmUp();

        assertThat(store.validAccessToken()).contains("acc-1");
    }
}
