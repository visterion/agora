package de.visterion.agora.trading.saxo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sizes the protective legs that {@code flatten} must put back after a partial close.
 *
 * <p><b>Monotonicity is the load-bearing rule:</b> no leg is ever restored larger than it was.
 * Anything else can turn an unrelated resting order into a position-sized one — a 5-share sell
 * limit next to the stop must shrink to 2, never grow to 23. Together with
 * {@code Σ qty <= remaining} this is what keeps total opposite-side interest from exceeding the
 * holding, which is what would produce an unintended reverse position once a leg triggers.
 *
 * <p>Stop legs are normalised over their own sum so the result is exact even when the legs never
 * matched the position (they can outsize it after a manual partial close in the broker UI —
 * {@code lookupRelatedOrders} filters by Uic alone). Limit legs are only ever shrunk, over
 * {@code available}, so the two groups can never claim the same shares twice.
 */
public final class LegAllocation {

    private LegAllocation() {}

    public record Sized(ProtectiveLeg leg, BigDecimal qty) {}

    /**
     * @param sized     legs to place, in the order they should be placed (tightest stop first)
     * @param collapsed true when the remainder was too small for one share per stop leg and all
     *                  protection was folded into the tightest one
     * @param target    the stop quantity actually covered — less than {@code remaining} when the
     *                  position was already under-protected before the trim
     * @param warning   non-null when the caller must surface a discrepancy rather than absorb it
     */
    public record Result(List<Sized> sized, boolean collapsed, BigDecimal target, String warning) {}

    public static Result allocate(List<ProtectiveLeg> legs, BigDecimal remaining, BigDecimal available) {
        List<ProtectiveLeg> stops = legs.stream().filter(ProtectiveLeg::isStop).toList();
        List<ProtectiveLeg> limits = legs.stream().filter(l -> !l.isStop()).toList();

        BigDecimal sumStop = stops.stream().map(ProtectiveLeg::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal target = sumStop.min(remaining);

        List<Sized> sized = new ArrayList<>();
        boolean collapsed = false;
        String warning = null;

        if (sumStop.signum() > 0 && target.signum() > 0) {
            List<ProtectiveLeg> byTightness = stops.stream().sorted(tightestFirst()).toList();

            if (target.compareTo(BigDecimal.valueOf(stops.size())) < 0) {
                // Not enough shares for one per leg: greedy-fill down tightness order instead of
                // dumping the whole target on the tightest leg — that would grow it past its own
                // amount whenever a foreign leg with a smaller amount sorts first.
                collapsed = true;
                BigDecimal remainingTarget = target;
                for (ProtectiveLeg l : byTightness) {
                    if (remainingTarget.signum() <= 0) break;
                    BigDecimal q = l.amount().min(remainingTarget);
                    if (q.signum() > 0) {
                        sized.add(new Sized(l, q));
                        remainingTarget = remainingTarget.subtract(q);
                    }
                }
            } else {
                sized.addAll(distribute(byTightness, sumStop, target));
            }

            if (target.compareTo(remaining) < 0) {
                warning = "protective stops covered only " + target.toPlainString()
                        + " of the remaining " + remaining.toPlainString()
                        + " shares — the position was already under-protected before this close";
            }
        } else if (!stops.isEmpty() && remaining.signum() > 0) {
            // remaining == 0 means there is nothing left to restore, not that the stop legs
            // summed to zero — those are different facts and only the second is a warning.
            warning = "no usable stop quantity to restore (sum of cancelled stop legs was zero)";
        }

        for (ProtectiveLeg l : limits) {
            BigDecimal q = l.amount().min(
                    l.amount().multiply(remaining).divide(available, 0, RoundingMode.DOWN));
            if (q.signum() > 0) sized.add(new Sized(l, q));
        }

        return new Result(List.copyOf(sized), collapsed, target, warning);
    }

    /** Proportional split with one guaranteed share per leg; the remainder tops up the tightest. */
    private static List<Sized> distribute(List<ProtectiveLeg> byTightness, BigDecimal sumStop, BigDecimal target) {
        List<BigDecimal> qty = new ArrayList<>();
        BigDecimal placed = BigDecimal.ZERO;
        for (ProtectiveLeg l : byTightness) {
            BigDecimal proportional = l.amount().multiply(target).divide(sumStop, 0, RoundingMode.DOWN);
            BigDecimal q = proportional.max(BigDecimal.ONE).min(l.amount());
            qty.add(q);
            placed = placed.add(q);
        }

        // The min-one floor can overshoot the target; trim from the widest leg backwards.
        for (int i = byTightness.size() - 1; i >= 0 && placed.compareTo(target) > 0; i--) {
            BigDecimal excess = placed.subtract(target);
            BigDecimal reducible = qty.get(i).subtract(BigDecimal.ONE);
            BigDecimal cut = reducible.min(excess);
            if (cut.signum() > 0) {
                qty.set(i, qty.get(i).subtract(cut));
                placed = placed.subtract(cut);
            }
        }

        // Rounding-down leaves a remainder; give it to the tightest leg that can still absorb it.
        for (int i = 0; i < byTightness.size() && placed.compareTo(target) < 0; i++) {
            BigDecimal room = byTightness.get(i).amount().subtract(qty.get(i));
            BigDecimal add = room.min(target.subtract(placed));
            if (add.signum() > 0) {
                qty.set(i, qty.get(i).add(add));
                placed = placed.add(add);
            }
        }

        List<Sized> out = new ArrayList<>();
        for (int i = 0; i < byTightness.size(); i++) {
            if (qty.get(i).signum() > 0) out.add(new Sized(byTightness.get(i), qty.get(i)));
        }
        return out;
    }

    /**
     * Tightest first: highest price protects a long (Sell legs), lowest protects a short (Buy legs).
     * Equal prices are the NORMAL case — the stop ratchet moves both legs to the same level — so
     * the tie-break must be deterministic: larger original amount, then smallest order id.
     */
    private static Comparator<ProtectiveLeg> tightestFirst() {
        return (a, b) -> {
            boolean buySide = "Buy".equalsIgnoreCase(a.buySell());
            int byPrice = buySide ? a.price().compareTo(b.price()) : b.price().compareTo(a.price());
            if (byPrice != 0) return byPrice;
            int byAmount = b.amount().compareTo(a.amount());
            if (byAmount != 0) return byAmount;
            return a.orderId().compareTo(b.orderId());
        };
    }
}
