package de.visterion.agora.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mutating broker ops (submitBracket, modifyBracket, flatten, cancel) must require full live
 * access on LIVE connections, even for callers whose readonly-live token already grants
 * {@link LiveAccessGuard#canSee}. Reads stay gated on canSee only.
 */
class BrokerServiceGateTest {

    private static final String LIVE_CONN = "live-conn";
    private static final String PAPER_CONN = "paper-conn";

    private static BrokerService service(LiveAccessGuard guard) {
        ConnectionConfig liveCfg = new ConnectionConfig();
        liveCfg.setProvider("stub");
        liveCfg.setEnvironment(ConnectionConfig.Environment.LIVE);
        liveCfg.setKeyId("k");
        liveCfg.setSecret("s");

        ConnectionConfig paperCfg = new ConnectionConfig();
        paperCfg.setProvider("stub");
        paperCfg.setEnvironment(ConnectionConfig.Environment.PAPER);
        paperCfg.setKeyId("k");
        paperCfg.setSecret("s");

        ConnectionsProperties props = new ConnectionsProperties();
        props.setConnections(Map.of(LIVE_CONN, liveCfg, PAPER_CONN, paperCfg));

        BrokerProvider provider = new StubBroker();
        BrokerProviderFactory factory = new BrokerProviderFactory() {
            public String provider() { return "stub"; }
            public BrokerProvider create(String connectionId, ConnectionConfig c) { return provider; }
        };

        ConnectionRegistry registry = new ConnectionRegistry(props, List.of(factory));
        return new BrokerService(registry, guard);
    }

    private static BracketOrderRequest anyBracketRequest() {
        return new BracketOrderRequest("NVDA", "buy", BigDecimal.ONE, "market",
                "day", null, null, null, null, "ref-1");
    }

    @Test
    void readonlyLiveTokenCanReadButNotMutate() {
        var service = service(new LiveAccessGuard(Set.of("live-1"), Set.of("ro-1"), () -> "ro-1"));

        assertThat(service.account(LIVE_CONN)).isNotNull();          // read OK

        assertThatThrownBy(() -> service.flatten(LIVE_CONN, "NVDA", null, null))
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("live");
        assertThatThrownBy(() -> service.submitBracket(LIVE_CONN, anyBracketRequest()))
                .isInstanceOf(BrokerException.class);
        assertThatThrownBy(() -> service.modifyBracket(LIVE_CONN, "oid", "NVDA", null, null))
                .isInstanceOf(BrokerException.class);
        assertThatThrownBy(() -> service.cancel(LIVE_CONN, "oid"))
                .isInstanceOf(BrokerException.class);
    }

    @Test
    void fullLiveTokenStillMutates() {
        var service = service(new LiveAccessGuard(Set.of("live-1"), Set.of("ro-1"), () -> "live-1"));

        assertThat(service.account(LIVE_CONN)).isNotNull();
        assertThat(service.flatten(LIVE_CONN, "NVDA", null, null)).isNotNull();
        assertThat(service.submitBracket(LIVE_CONN, anyBracketRequest())).isNotNull();
        assertThat(service.modifyBracket(LIVE_CONN, "oid", "NVDA", null, null)).isNotNull();
        assertThat(service.cancel(LIVE_CONN, "oid")).isNotNull();
    }

    @Test
    void paperConnectionUnaffected() {
        var service = service(new LiveAccessGuard(Set.of("live-1"), Set.of("ro-1"), () -> null));

        assertThat(service.account(PAPER_CONN)).isNotNull();
        assertThat(service.flatten(PAPER_CONN, "NVDA", null, null)).isNotNull();
        assertThat(service.submitBracket(PAPER_CONN, anyBracketRequest())).isNotNull();
        assertThat(service.modifyBracket(PAPER_CONN, "oid", "NVDA", null, null)).isNotNull();
        assertThat(service.cancel(PAPER_CONN, "oid")).isNotNull();
    }

    static class StubBroker implements BrokerProvider {
        public String name() { return "stub"; }
        public OrderResult submitBracket(BracketOrderRequest r) { return OrderResult.accepted("oid", r.clientRef(), "accepted"); }
        public OrderResult modifyBracket(String id, String symbol, BigDecimal s, BigDecimal t) { return OrderResult.accepted(id, null, "replaced"); }
        public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty) { return OrderResult.accepted("oid", null, "accepted"); }
        public List<Position> positions() { return List.of(); }
        public List<Order> orders(String status) { return List.of(); }
        public Account account() { return new Account("acc", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, "USD", "ACTIVE"); }
        public Order orderByClientRef(String ref) { return new Order("oid", ref, "AAPL", "buy", BigDecimal.ONE, "limit", "new"); }
        public OrderResult cancel(String brokerOrderId) { return OrderResult.accepted(brokerOrderId, null, "canceled"); }
        public void probe() {}
    }
}
