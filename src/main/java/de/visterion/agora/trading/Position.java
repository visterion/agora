package de.visterion.agora.trading;

import java.math.BigDecimal;

public record Position(String symbol, String description, BigDecimal qty, BigDecimal avgEntryPrice,
                       BigDecimal marketValue, BigDecimal unrealizedPl, String currency,
                       String assetType, String valueDate) {}
