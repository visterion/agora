package de.visterion.agora.fetch.reference.change;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Aggregates every {@link IndexChangeProvider} bean into a single list of index constituent
 * changes, mirroring {@code MarketDataService}'s ordered-provider pattern. Providers are
 * consulted in {@link IndexChangeProvider#order()} order; the first provider to report a
 * given change wins (deduplicated on {@code (index, upper(symbol), action, effectiveDate)}).
 *
 * <p>Never throws: a provider that violates its no-throw contract is caught and skipped, and
 * an all-unavailable result degrades to an empty list. Results with an announcement date older
 * than {@code lookbackDays} are dropped so callers can bound how far back the window reaches.
 */
@Component
public class IndexChangeService {

    private static final Logger log = LoggerFactory.getLogger(IndexChangeService.class);

    private final List<IndexChangeProvider> providers;
    private final Clock clock;

    @Autowired
    public IndexChangeService(List<IndexChangeProvider> providers) {
        this(providers, Clock.systemDefaultZone());
    }

    // Test constructor with an injectable clock so the lookback cutoff is deterministic.
    IndexChangeService(List<IndexChangeProvider> providers, Clock clock) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(IndexChangeProvider::order))
                .toList();
        this.clock = clock;
    }

    /**
     * Aggregated, deduplicated changes for an index. {@code lookbackDays <= 0} disables the
     * announcement-date cutoff (return everything the providers know). Never throws.
     */
    public List<IndexChange> changes(String index, int lookbackDays) {
        LocalDate cutoff = lookbackDays > 0 ? LocalDate.now(clock).minusDays(lookbackDays) : null;
        Set<String> seen = new LinkedHashSet<>();
        List<IndexChange> out = new ArrayList<>();
        for (IndexChangeProvider p : providers) {
            List<IndexChange> pc;
            try {
                pc = p.changes(index);
            } catch (RuntimeException e) {
                // Defensive: the contract says providers never throw, but one misbehaving
                // provider must not abort the whole aggregation.
                log.warn("index-change provider {} failed for {}: {}",
                        p.getClass().getSimpleName(), index, e.toString());
                continue;
            }
            if (pc == null) continue;
            for (IndexChange c : pc) {
                if (c == null) continue;
                if (cutoff != null && c.announcementDate() != null && c.announcementDate().isBefore(cutoff))
                    continue;
                if (seen.add(dedupKey(c))) out.add(c);
            }
        }
        return out;
    }

    private static String dedupKey(IndexChange c) {
        String sym = c.symbol() == null ? "" : c.symbol().toUpperCase(Locale.ROOT);
        return c.index() + '|' + sym + '|' + c.action() + '|' + c.effectiveDate();
    }
}
