package de.visterion.agora.fetch.reference.change;

import java.util.List;

/**
 * A pluggable source of index constituent changes (additions/removals) for a stock index.
 * Implement as a Spring {@code @Component} to join the ordered aggregation chain in
 * {@link IndexChangeService}.
 *
 * <p>Contract: {@link #changes(String)} NEVER throws and returns an empty list when the
 * source is unavailable or the index is unknown to this provider — callers treat "no
 * changes" and "source down" identically (graceful degradation, mirroring the tool layer).
 */
public interface IndexChangeProvider {

    /** Aggregation order (lower first); the first provider wins on duplicate changes. */
    int order();

    /** Changes for an index; empty when unavailable or unknown. Never throws. */
    List<IndexChange> changes(String index);
}
