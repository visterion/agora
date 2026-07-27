package de.visterion.agora.fetch.earnings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Merges per-provider earnings lists into one. Sources routinely date the same event a day
 * apart (a pre-market call on D vs an after-hours call on D-1), so events are clustered
 * rather than keyed on an exact date.
 *
 * <p>Clustering is <em>anchor-based</em>, not transitive: a plain "within one day" relation
 * would chain D → D+1 → D+2 into a single event. Anchors come from the highest-priority
 * provider that returned data; every other event joins the nearest anchor within one day for
 * the same ticker, or becomes an anchor itself.
 *
 * <p>On field conflict the higher-priority provider wins, except that a populated value always
 * beats {@code null} — that is how a secondary source fills gaps instead of merely duplicating.
 */
public final class EarningsMerger {

    private EarningsMerger() {}

    private static final class Cluster {
        final String symbol;
        final LocalDate date;
        EarningsEvent merged;
        Cluster(EarningsEvent seed) {
            this.symbol = seed.symbol().toUpperCase();
            this.date = seed.date();
            this.merged = new EarningsEvent(this.symbol, seed.date(), seed.epsEstimate(),
                    seed.epsActual(), seed.epsSurprisePct(), seed.revenueEstimate(),
                    seed.revenueActual());
        }
    }

    /** @param byProviderInOrder per-provider results, ordered by provider priority ascending. */
    public static List<EarningsEvent> merge(List<List<EarningsEvent>> byProviderInOrder) {
        List<Cluster> clusters = new ArrayList<>();

        for (List<EarningsEvent> providerEvents : byProviderInOrder) {
            if (providerEvents == null) continue;
            for (EarningsEvent e : providerEvents) {
                if (e == null || e.symbol() == null || e.date() == null) continue;
                Cluster target = nearestAnchor(clusters, e);
                if (target == null) clusters.add(new Cluster(e));
                else target.merged = fill(target.merged, e);
            }
        }

        List<EarningsEvent> out = new ArrayList<>(clusters.size());
        for (Cluster c : clusters) out.add(c.merged);
        out.sort(Comparator.comparing(EarningsEvent::date).thenComparing(EarningsEvent::symbol));
        return out;
    }

    /** Nearest existing cluster for this ticker within one day, or null. */
    private static Cluster nearestAnchor(List<Cluster> clusters, EarningsEvent e) {
        String sym = e.symbol().toUpperCase();
        Cluster best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Cluster c : clusters) {
            if (!c.symbol.equals(sym)) continue;
            long d = Math.abs(c.date.toEpochDay() - e.date().toEpochDay());
            if (d > 1) continue;
            if (d < bestDistance) { bestDistance = d; best = c; }
        }
        return best;
    }

    /** Keeps the anchor's values; only fills fields the anchor left null. */
    private static EarningsEvent fill(EarningsEvent anchor, EarningsEvent other) {
        return new EarningsEvent(
                anchor.symbol(),
                anchor.date(),
                pick(anchor.epsEstimate(), other.epsEstimate()),
                pick(anchor.epsActual(), other.epsActual()),
                pick(anchor.epsSurprisePct(), other.epsSurprisePct()),
                pick(anchor.revenueEstimate(), other.revenueEstimate()),
                pick(anchor.revenueActual(), other.revenueActual()));
    }

    private static BigDecimal pick(BigDecimal preferred, BigDecimal fallback) {
        return preferred != null ? preferred : fallback;
    }
}
