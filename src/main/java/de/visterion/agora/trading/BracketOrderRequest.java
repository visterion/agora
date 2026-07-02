package de.visterion.agora.trading;

import java.math.BigDecimal;

public record BracketOrderRequest(String symbol, String side, BigDecimal qty, String type,
                                  String timeInForce, BigDecimal limitPrice,
                                  BigDecimal stopLossStop, BigDecimal stopLossLimit,
                                  BigDecimal takeProfitLimit, String clientRef) {}
