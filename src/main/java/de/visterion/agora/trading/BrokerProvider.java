package de.visterion.agora.trading;

import java.math.BigDecimal;
import java.util.List;

/** A broker. Implement as a @Component. No per-call fallback (brokers are not interchangeable). */
public interface BrokerProvider {
    String name();
    OrderResult submitBracket(BracketOrderRequest req);
    OrderResult modifyBracket(String brokerOrderId, BigDecimal newStop, BigDecimal newTarget);
    OrderResult flatten(String symbol);
    List<Position> positions();
    List<Order> orders(String status);
    Account account();
    Order orderByClientRef(String clientRef);
}
