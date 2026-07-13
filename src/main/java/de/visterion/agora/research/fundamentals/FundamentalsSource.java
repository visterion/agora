package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;

/** Provider-neutral company-fundamentals source. Implementations MUST throw
 *  MarketDataException(UNAVAILABLE) on transient failure (so nothing is cached and
 *  the tool reports unavailable); an absent concept in a returned SourceResult is
 *  interpreted per its {@link AbsenceSemantics}. */
public interface FundamentalsSource {
    SourceResult facts(Instrument inst);
}
