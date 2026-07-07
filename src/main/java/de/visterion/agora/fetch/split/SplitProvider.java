package de.visterion.agora.fetch.split;

import java.util.List;

/** A pluggable stock-split source. Implement as a @Component to join the fallback chain. */
public interface SplitProvider {
    String name();
    /** Splits for a symbol; empty list = "no splits known to this source" (not an error). */
    List<SplitEvent> splits(String symbol);
}
