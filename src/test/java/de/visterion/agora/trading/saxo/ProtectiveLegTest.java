package de.visterion.agora.trading.saxo;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectiveLegTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) { return M.readTree(s); }

    /** The real production payload for IMAX stop leg 5039446538, captured 2026-08-04. */
    @Test
    void readsTopLevelShapeWithPriceAndDuration() {
        JsonNode n = json("""
            {"Amount":22.0,"BuySell":"Sell","OpenOrderType":"StopIfTraded","OrderId":"5039446538",
             "OrderRelation":"StandAlone","Price":45.49,
             "Duration":{"DurationType":"GoodTillCancel"},"Status":"Working","Uic":50720,
             "AssetType":"Stock"}""");

        ProtectiveLeg leg = ProtectiveLeg.from(n).orElseThrow();

        assertThat(leg.orderId()).isEqualTo("5039446538");
        assertThat(leg.price()).isEqualByComparingTo("45.49");
        assertThat(leg.amount()).isEqualByComparingTo("22");
        assertThat(leg.buySell()).isEqualTo("Sell");
        assertThat(leg.openOrderType()).isEqualTo("StopIfTraded");
        assertThat(leg.assetType()).isEqualTo("Stock");
        assertThat(leg.duration().path("DurationType").asString("")).isEqualTo("GoodTillCancel");
        assertThat(leg.isStop()).isTrue();
    }

    @Test
    void readsEmbeddedShapeWithOrderPriceAndOrderDuration() {
        JsonNode n = json("""
            {"Amount":10.0,"BuySell":"Sell","OpenOrderType":"Limit","OrderId":"999",
             "OrderPrice":51.25,"OrderDuration":{"DurationType":"DayOrder"},"AssetType":"Stock"}""");

        ProtectiveLeg leg = ProtectiveLeg.from(n).orElseThrow();

        assertThat(leg.price()).isEqualByComparingTo("51.25");
        assertThat(leg.duration().path("DurationType").asString("")).isEqualTo("DayOrder");
        assertThat(leg.isStop()).isFalse();
    }

    /** No price in either shape: the leg is NOT reconstructible. Never fall back to zero. */
    @Test
    void returnsEmptyWhenNeitherPriceFieldIsPresent() {
        JsonNode n = json("""
            {"Amount":22.0,"BuySell":"Sell","OpenOrderType":"StopIfTraded","OrderId":"5039446538"}""");

        assertThat(ProtectiveLeg.from(n)).isEmpty();
    }

    @Test
    void returnsEmptyWhenPriceIsZero() {
        JsonNode n = json("""
            {"Amount":22.0,"BuySell":"Sell","OpenOrderType":"StopIfTraded","OrderId":"1","Price":0}""");

        assertThat(ProtectiveLeg.from(n)).isEmpty();
    }

    @Test
    void returnsEmptyWhenOrderIdIsMissing() {
        JsonNode n = json("""
            {"Amount":22.0,"BuySell":"Sell","OpenOrderType":"StopIfTraded","Price":45.49}""");

        assertThat(ProtectiveLeg.from(n)).isEmpty();
    }

    @Test
    void returnsEmptyWhenAmountIsZero() {
        JsonNode n = json("""
            {"Amount":0,"BuySell":"Sell","OpenOrderType":"StopIfTraded","OrderId":"1","Price":45.49}""");

        assertThat(ProtectiveLeg.from(n)).isEmpty();
    }

    @Test
    void carriesStopLimitPriceWhenPresent() {
        JsonNode n = json("""
            {"Amount":5,"BuySell":"Sell","OpenOrderType":"StopLimit","OrderId":"7","Price":45.49,
             "StopLimitPrice":45.40}""");

        assertThat(ProtectiveLeg.from(n).orElseThrow().stopLimitPrice()).isEqualByComparingTo("45.40");
    }

    @Test
    void stopLimitPriceIsNullWhenAbsent() {
        JsonNode n = json("""
            {"Amount":5,"BuySell":"Sell","OpenOrderType":"StopIfTraded","OrderId":"7","Price":45.49}""");

        assertThat(ProtectiveLeg.from(n).orElseThrow().stopLimitPrice()).isNull();
    }

    @Test
    void defaultsAssetTypeToStockWhenAbsent() {
        JsonNode n = json("""
            {"Amount":5,"BuySell":"Sell","OpenOrderType":"StopIfTraded","OrderId":"7","Price":45.49}""");

        assertThat(ProtectiveLeg.from(n).orElseThrow().assetType()).isEqualTo("Stock");
    }
}
