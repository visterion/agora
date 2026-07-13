package de.visterion.agora.research.fundamentals;

import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import java.util.List;
import java.util.Map;

/** A successful fundamentals fetch. Transient failure is signalled by a thrown
 *  MarketDataException, never by this type. */
public record SourceResult(Map<FundamentalConcept, ConceptSeries> concepts, AbsenceSemantics semantics) {
    public ConceptSeries series(FundamentalConcept c) {
        return concepts.getOrDefault(c, new ConceptSeries(null, List.of()));
    }
}
