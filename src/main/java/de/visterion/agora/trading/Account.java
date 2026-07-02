package de.visterion.agora.trading;

import java.math.BigDecimal;

public record Account(String accountId, BigDecimal equity, BigDecimal buyingPower,
                      BigDecimal cash, String currency, String status) {}
