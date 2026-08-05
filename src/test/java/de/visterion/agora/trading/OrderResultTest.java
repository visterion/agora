package de.visterion.agora.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderResultTest {

    @Test
    void existingFactoriesLeaveLegsEmptyAndNotCollapsed() {
        OrderResult r = OrderResult.accepted("1", null, "accepted");

        assertThat(r.protectiveLegs()).isEmpty();
        assertThat(r.legsCollapsed()).isFalse();
    }

    @Test
    void acceptedFlattenCanCarryRestoredLegs() {
        var legs = List.of(new RestoredLeg("old-1", "new-1", new BigDecimal("12"), new BigDecimal("45.49")));

        OrderResult r = OrderResult.acceptedWithLegs("1", null, "accepted",
                new BigDecimal("23"), new BigDecimal("23"), null, legs, false);

        assertThat(r.accepted()).isTrue();
        assertThat(r.protectiveLegs()).hasSize(1);
        assertThat(r.protectiveLegs().getFirst().replaces()).isEqualTo("old-1");
        assertThat(r.remainingQty()).isEqualByComparingTo("23");
    }

    /** A rollback hands back NEW ids; the rejection must carry them or the book keeps dead ones. */
    @Test
    void rejectionCanCarryTheRolledBackLegs() {
        var legs = List.of(new RestoredLeg("old-1", "new-1", new BigDecimal("24"), new BigDecimal("45.49")));

        OrderResult r = OrderResult.rejectedWithLegs("close failed", "LEG_RESTORE_FAILED", legs, false);

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_RESTORE_FAILED");
        assertThat(r.protectiveLegs()).hasSize(1);
    }

    @Test
    void collapseFlagSurvivesOnBothBranches() {
        assertThat(OrderResult.acceptedWithLegs("1", null, "accepted", null, null, null, List.of(), true)
                .legsCollapsed()).isTrue();
        assertThat(OrderResult.rejectedWithLegs("x", "Y", List.of(), true).legsCollapsed()).isTrue();
    }
}
