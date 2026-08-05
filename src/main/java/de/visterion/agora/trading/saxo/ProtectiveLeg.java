package de.visterion.agora.trading.saxo;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * One protective order that {@code flatten} cancelled and may have to put back.
 *
 * <p>Saxo returns a protective order in two shapes: a top-level order carries {@code Price} and
 * {@code Duration}, an embedded bracket leg carries {@code OrderPrice} and {@code OrderDuration}
 * (see {@code SaxoBrokerProvider}'s javadoc: "a parent never carries OrderPrice, a leg never
 * carries Price"). Reading the wrong one silently yields zero, because {@code bd(JsonNode)}
 * zero-defaults — and a stop at price 0 is worse than no trim at all. This factory therefore
 * reads both and refuses the leg when neither is usable.
 */
public record ProtectiveLeg(String orderId, String openOrderType, String buySell, String assetType,
                            BigDecimal price, BigDecimal stopLimitPrice, JsonNode duration,
                            BigDecimal amount) {

    /** Empty when the node is not a reconstructible protective leg — never a zero-priced guess. */
    public static Optional<ProtectiveLeg> from(JsonNode node) {
        String orderId = node.path("OrderId").asString(null);
        if (orderId == null || orderId.isBlank()) return Optional.empty();

        BigDecimal price = decimalOrNull(node, "Price");
        if (price == null) price = decimalOrNull(node, "OrderPrice");
        if (price == null || price.signum() <= 0) return Optional.empty();

        BigDecimal amount = decimalOrNull(node, "Amount");
        if (amount == null || amount.signum() <= 0) return Optional.empty();

        JsonNode duration = node.has("Duration") ? node.path("Duration") : node.path("OrderDuration");

        return Optional.of(new ProtectiveLeg(
                orderId,
                node.path("OpenOrderType").asString(""),
                node.path("BuySell").asString(""),
                node.path("AssetType").asString("Stock"),
                price,
                decimalOrNull(node, "StopLimitPrice"),
                duration,
                amount));
    }

    /** A stop of any flavour (StopIfTraded, StopLimit, TrailingStopIfTraded, …). */
    public boolean isStop() { return openOrderType != null && openOrderType.contains("Stop"); }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isNumber() ? n.decimalValue() : null;
    }
}
