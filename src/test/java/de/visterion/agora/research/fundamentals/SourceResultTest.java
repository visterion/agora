package de.visterion.agora.research.fundamentals;

import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SourceResultTest {
    @Test
    void seriesReturnsEmptyForAbsentConcept() {
        SourceResult r = new SourceResult(Map.of(), AbsenceSemantics.SPARSE);
        ConceptSeries s = r.series(FundamentalConcept.TOTAL_ASSETS);
        assertThat(s.datapoints()).isEmpty();
        assertThat(s.unit()).isNull();
        assertThat(r.semantics()).isEqualTo(AbsenceSemantics.SPARSE);
    }
}
