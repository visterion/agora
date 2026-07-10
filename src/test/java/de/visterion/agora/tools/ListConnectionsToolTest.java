package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ListConnectionsToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static ConnectionConfig cfg(String provider, ConnectionConfig.Environment env) {
        ConnectionConfig c = new ConnectionConfig();
        c.setProvider(provider);
        c.setEnvironment(env);
        c.setKeyId("k");
        c.setSecret("s");
        return c;
    }

    private static ConnectionRegistry registry() {
        Map<String, ConnectionConfig> conns = new LinkedHashMap<>();
        conns.put("paper-1", cfg("stub", ConnectionConfig.Environment.PAPER));
        conns.put("live-1", cfg("stub", ConnectionConfig.Environment.LIVE));
        ConnectionsProperties props = new ConnectionsProperties();
        props.setConnections(conns);
        BrokerProviderFactory f = new BrokerProviderFactory() {
            public String provider() { return "stub"; }
            public BrokerProvider create(String connectionId, ConnectionConfig c) { return null; }  // provider unused by this tool
        };
        return new ConnectionRegistry(props, List.of(f));
    }

    private ListConnectionsTool tool(ConnectionRegistry reg, String presentedToken) {
        return new ListConnectionsTool(reg, new LiveAccessGuard(Set.of("live-token"), () -> presentedToken));
    }

    @Test
    void namespaceIsTrading() {
        assertThat(tool(registry(), null).namespace()).isEqualTo("trading");
        assertThat(tool(registry(), null).name()).isEqualTo("list_connections");
    }

    @Test
    void nonLiveTokenSeesOnlyPaperConnections() {
        var r = tool(registry(), "trade-token").call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        var arr = r.output().get("connections");
        assertThat(arr.size()).isEqualTo(1);
        assertThat(arr.get(0).get("id").asString()).isEqualTo("paper-1");
        assertThat(arr.get(0).get("environment").asString()).isEqualTo("paper");
        assertThat(arr.get(0).get("status").asString()).isEqualTo("unknown");
    }

    @Test
    void liveTokenSeesAllConnections() {
        var r = tool(registry(), "live-token").call(mapper.createObjectNode());
        var arr = r.output().get("connections");
        assertThat(arr.size()).isEqualTo(2);
        assertThat(arr.get(1).get("id").asString()).isEqualTo("live-1");
        assertThat(arr.get(1).get("environment").asString()).isEqualTo("live");
    }

    @Test
    void probedAtShownWhenProbed() {
        var reg = registry();
        reg.get("paper-1").orElseThrow().setProbeStatus(ProbeStatus.ok(Instant.parse("2026-07-07T10:00:00Z")));
        var r = tool(reg, "trade-token").call(mapper.createObjectNode());
        var c0 = r.output().get("connections").get(0);
        assertThat(c0.get("status").asString()).isEqualTo("ok");
        assertThat(c0.get("probedAt").asString()).isEqualTo("2026-07-07T10:00:00Z");
    }

    @Test
    void outputNeverContainsCredentials() {
        var r = tool(registry(), "live-token").call(mapper.createObjectNode());
        assertThat(r.output().toString()).doesNotContain("keyId").doesNotContain("secret").doesNotContain("\"k\"");
    }
}
