package de.visterion.agora.trading.saxo;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegAllocationTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ProtectiveLeg stop(String id, String amount, String price, String side) {
        ObjectNode d = M.createObjectNode();
        d.put("DurationType", "GoodTillCancel");
        return new ProtectiveLeg(id, "StopIfTraded", side, "Stock",
                new BigDecimal(price), null, d, new BigDecimal(amount));
    }

    private static ProtectiveLeg limit(String id, String amount, String price, String side) {
        ObjectNode d = M.createObjectNode();
        d.put("DurationType", "GoodTillCancel");
        return new ProtectiveLeg(id, "Limit", side, "Stock",
                new BigDecimal(price), null, d, new BigDecimal(amount));
    }

    private static BigDecimal qtyOf(LegAllocation.Result r, String orderId) {
        return r.sized().stream().filter(s -> s.leg().orderId().equals(orderId))
                .map(LegAllocation.Sized::qty).findFirst().orElse(null);
    }

    /** The IMAX case: 46 shares, two stops 24/22 at the same price, trim 0.5. */
    @Test
    void splitsTwoStopsProportionallyAndSumsToRemaining() {
        var r = LegAllocation.allocate(
                List.of(stop("A", "24", "45.49", "Sell"), stop("B", "22", "45.49", "Sell")),
                new BigDecimal("23"), new BigDecimal("46"));

        assertThat(qtyOf(r, "A")).isEqualByComparingTo("12");
        assertThat(qtyOf(r, "B")).isEqualByComparingTo("11");
        assertThat(r.target()).isEqualByComparingTo("23");
        assertThat(r.collapsed()).isFalse();
        assertThat(r.warning()).isNull();
    }

    @Test
    void singleStopTakesTheWholeRemainder() {
        var r = LegAllocation.allocate(List.of(stop("A", "23", "46.55", "Sell")),
                new BigDecimal("12"), new BigDecimal("23"));

        assertThat(qtyOf(r, "A")).isEqualByComparingTo("12");
        assertThat(r.collapsed()).isFalse();
    }

    /**
     * MONOTONICITY — the failure the second spec draft introduced. A foreign 5-share sell limit
     * next to the stop must shrink to 2, never grow to 23.
     */
    @Test
    void limitLegShrinksOverAvailableAndNeverGrows() {
        var r = LegAllocation.allocate(
                List.of(stop("S", "46", "45.49", "Sell"), limit("L", "5", "60.00", "Sell")),
                new BigDecimal("23"), new BigDecimal("46"));

        assertThat(qtyOf(r, "S")).isEqualByComparingTo("23");
        assertThat(qtyOf(r, "L")).isEqualByComparingTo("2");
    }

    @Test
    void noLegEverExceedsItsOriginalAmount() {
        var r = LegAllocation.allocate(
                List.of(stop("A", "3", "45.00", "Sell"), stop("B", "2", "45.49", "Sell")),
                new BigDecimal("40"), new BigDecimal("46"));

        assertThat(qtyOf(r, "A")).isEqualByComparingTo("3");
        assertThat(qtyOf(r, "B")).isEqualByComparingTo("2");
        assertThat(r.target()).isEqualByComparingTo("5");
    }

    /** Pre-existing under-protection is reported, not healed — topping up is S7c's job. */
    @Test
    void reportsWhenStopsCoveredLessThanTheRemainder() {
        var r = LegAllocation.allocate(List.of(stop("A", "5", "45.49", "Sell")),
                new BigDecimal("23"), new BigDecimal("46"));

        assertThat(r.target()).isEqualByComparingTo("5");
        assertThat(r.warning()).contains("5").contains("23");
    }

    /** The corner case from review round 1: two stops totalling 46 on a 23-share position. */
    @Test
    void handlesLegsThatOutsizeThePosition() {
        var r = LegAllocation.allocate(
                List.of(stop("A", "24", "45.49", "Sell"), stop("B", "22", "45.49", "Sell")),
                new BigDecimal("11"), new BigDecimal("23"));

        assertThat(r.target()).isEqualByComparingTo("11");
        assertThat(r.sized()).allSatisfy(s -> assertThat(s.qty()).isPositive());
        assertThat(r.sized().stream().map(LegAllocation.Sized::qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("11");
    }

    @Test
    void everyStopLegKeepsAtLeastOneShare() {
        var r = LegAllocation.allocate(
                List.of(stop("A", "45", "45.49", "Sell"), stop("B", "1", "45.49", "Sell")),
                new BigDecimal("2"), new BigDecimal("46"));

        assertThat(qtyOf(r, "A")).isEqualByComparingTo("1");
        assertThat(qtyOf(r, "B")).isEqualByComparingTo("1");
        assertThat(r.collapsed()).isFalse();
    }

    @Test
    void collapsesToTheTightestLegWhenRemainderIsSmallerThanTheLegCount() {
        var r = LegAllocation.allocate(
                List.of(stop("A", "24", "45.20", "Sell"), stop("B", "22", "45.49", "Sell")),
                BigDecimal.ONE, new BigDecimal("46"));

        assertThat(r.collapsed()).isTrue();
        assertThat(r.sized()).hasSize(1);
        assertThat(r.sized().getFirst().leg().orderId()).isEqualTo("B");
        assertThat(r.sized().getFirst().qty()).isEqualByComparingTo("1");
    }

    /** Equal prices are the NORMAL case — ratchetTwoLegs puts both legs on the same level. */
    @Test
    void tieBreaksOnAmountThenOrderIdWhenPricesAreEqual() {
        var r = LegAllocation.allocate(
                List.of(stop("Z", "22", "45.49", "Sell"), stop("A", "24", "45.49", "Sell")),
                BigDecimal.ONE, new BigDecimal("46"));

        assertThat(r.sized().getFirst().leg().orderId()).isEqualTo("A"); // larger amount wins
    }

    @Test
    void tieBreaksOnOrderIdWhenPriceAndAmountAreEqual() {
        var r = LegAllocation.allocate(
                List.of(stop("9", "23", "45.49", "Sell"), stop("1", "23", "45.49", "Sell")),
                BigDecimal.ONE, new BigDecimal("46"));

        assertThat(r.sized().getFirst().leg().orderId()).isEqualTo("1");
    }

    /** For a SHORT position the protective legs are Buy, and "tightest" is the LOWEST price. */
    @Test
    void tightestIsTheLowestPriceForBuyLegs() {
        var r = LegAllocation.allocate(
                List.of(stop("HIGH", "24", "60.00", "Buy"), stop("LOW", "22", "55.00", "Buy")),
                BigDecimal.ONE, new BigDecimal("46"));

        assertThat(r.sized().getFirst().leg().orderId()).isEqualTo("LOW");
    }

    @Test
    void roundingRemainderGoesToTheTightestStopLeg() {
        var r = LegAllocation.allocate(
                List.of(stop("A", "10", "45.20", "Sell"), stop("B", "10", "45.49", "Sell"),
                        stop("C", "10", "45.10", "Sell")),
                new BigDecimal("14"), new BigDecimal("30"));

        // floor(10 * 14/30) = 4 each -> 12, remainder 2 to the tightest (B)
        assertThat(qtyOf(r, "A")).isEqualByComparingTo("4");
        assertThat(qtyOf(r, "B")).isEqualByComparingTo("6");
        assertThat(qtyOf(r, "C")).isEqualByComparingTo("4");
    }

    @Test
    void remainderNeverPushesALegPastItsOriginalAmount() {
        var r = LegAllocation.allocate(
                List.of(stop("TIGHT", "5", "45.49", "Sell"), stop("WIDE", "20", "45.00", "Sell")),
                new BigDecimal("13"), new BigDecimal("25"));

        assertThat(qtyOf(r, "TIGHT")).isLessThanOrEqualTo(new BigDecimal("5"));
        assertThat(r.sized().stream().map(LegAllocation.Sized::qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("13");
    }

    @Test
    void emptyLegListYieldsEmptyResult() {
        var r = LegAllocation.allocate(List.of(), new BigDecimal("23"), new BigDecimal("46"));

        assertThat(r.sized()).isEmpty();
        assertThat(r.collapsed()).isFalse();
        assertThat(r.target()).isEqualByComparingTo("0");
    }

    @Test
    void onlyLimitLegsMeansNoStopTargetButLimitsStillShrink() {
        var r = LegAllocation.allocate(List.of(limit("L", "20", "60.00", "Sell")),
                new BigDecimal("23"), new BigDecimal("46"));

        assertThat(qtyOf(r, "L")).isEqualByComparingTo("10");
        assertThat(r.target()).isEqualByComparingTo("0");
    }

    @Test
    void limitLegThatShrinksToZeroIsDropped() {
        var r = LegAllocation.allocate(
                List.of(stop("S", "46", "45.49", "Sell"), limit("L", "1", "60.00", "Sell")),
                BigDecimal.ONE, new BigDecimal("46"));

        assertThat(qtyOf(r, "L")).isNull();
    }
}
