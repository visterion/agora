package de.visterion.agora.trading;

import java.math.BigDecimal;

public record Order(String brokerOrderId, String clientRef, String symbol, String side,
                    BigDecimal qty, String type, String status) {}
