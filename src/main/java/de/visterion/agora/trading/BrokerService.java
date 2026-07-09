package de.visterion.agora.trading;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Routes each trading call to the connection named by the caller. No default connection —
 * the target is always explicit. LIVE connections are visible and usable only for callers
 * whose token is in the live set; for everyone else a live id behaves exactly like an
 * unknown id (no enumeration oracle).
 */
@Component
public class BrokerService {

    private final ConnectionRegistry registry;
    private final LiveAccessGuard guard;

    public BrokerService(ConnectionRegistry registry, LiveAccessGuard guard) {
        this.registry = registry;
        this.guard = guard;
    }

    public OrderResult submitBracket(String connection, BracketOrderRequest r) { return resolve(connection).submitBracket(r); }
    public OrderResult modifyBracket(String connection, String id, BigDecimal stop, BigDecimal target) { return resolve(connection).modifyBracket(id, stop, target); }
    public OrderResult flatten(String connection, String symbol, BigDecimal fraction, BigDecimal qty) {
        return resolve(connection).flatten(symbol, fraction, qty);
    }
    public List<Position> positions(String connection) { return resolve(connection).positions(); }
    public List<Order> orders(String connection, String status) { return resolve(connection).orders(status); }
    public Account account(String connection) { return resolve(connection).account(); }
    public Order orderByClientRef(String connection, String ref) { return resolve(connection).orderByClientRef(ref); }
    public OrderResult cancel(String connection, String brokerOrderId) { return resolve(connection).cancel(brokerOrderId); }

    /** Lookup + visibility gate. Invisible (live without live token) == unknown. */
    private BrokerProvider resolve(String connection) {
        RegisteredConnection rc = registry.get(connection).orElse(null);
        if (rc == null || !guard.canSee(rc)) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "unknown or inactive connection: " + connection
                            + " (active: " + String.join(", ", visibleIds()) + ")", null);
        }
        return rc.provider();
    }

    private List<String> visibleIds() {
        return registry.active().stream()
                .filter(guard::canSee)
                .map(RegisteredConnection::id)
                .toList();
    }
}
