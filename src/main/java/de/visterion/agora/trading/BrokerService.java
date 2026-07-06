package de.visterion.agora.trading;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.List;

/** Holds the single active BrokerProvider and delegates. No fallback — a mid-order broker
 *  switch would be wrong. (Provider selected by which BrokerProvider @Component is present.) */
@Component
public class BrokerService {
    private final BrokerProvider provider;
    public BrokerService(BrokerProvider provider) { this.provider = provider; }
    public OrderResult submitBracket(BracketOrderRequest r) { return provider.submitBracket(r); }
    public OrderResult modifyBracket(String id, BigDecimal stop, BigDecimal target) { return provider.modifyBracket(id, stop, target); }
    public OrderResult flatten(String symbol) { return provider.flatten(symbol); }
    public List<Position> positions() { return provider.positions(); }
    public List<Order> orders(String status) { return provider.orders(status); }
    public Account account() { return provider.account(); }
    public Order orderByClientRef(String ref) { return provider.orderByClientRef(ref); }
    public OrderResult cancel(String brokerOrderId) { return provider.cancel(brokerOrderId); }
}
