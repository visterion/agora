package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.EdgarService.CompanyFacts;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EdgarFundamentalsSourceTest {
    private ConceptDatapoint dp(String end, double v) {
        return new ConceptDatapoint(null, LocalDate.parse(end), BigDecimal.valueOf(v), 2025, "FY", "10-K", LocalDate.parse(end));
    }

    @Test
    void mapsFirstNonEmptyRevenueSynonymWholesale_noMerge() {
        EdgarService edgar = mock(EdgarService.class);
        // "Revenues" empty, second synonym populated -> the second wins wholesale.
        CompanyFacts f = new CompanyFacts(Map.of(
            "RevenueFromContractWithCustomerExcludingAssessedTax",
                new ConceptSeries("USD", List.of(dp("2025-09-30", 400), dp("2024-09-30", 380))),
            "SalesRevenueNet", new ConceptSeries("USD", List.of(dp("2025-09-30", 999)))));
        when(edgar.companyFacts("AAPL", null)).thenReturn(f);

        EdgarFundamentalsSource src = new EdgarFundamentalsSource(edgar);
        SourceResult r = src.facts(Instrument.raw("AAPL"));

        assertThat(r.semantics()).isEqualTo(AbsenceSemantics.COMPLETE);
        ConceptSeries rev = r.series(FundamentalConcept.REVENUE);
        assertThat(rev.datapoints()).extracting(ConceptDatapoint::value)
            .containsExactly(BigDecimal.valueOf(400.0), BigDecimal.valueOf(380.0)); // not the 999 series
    }

    @Test
    void absentLongTermDebtYieldsEmptySeriesUnderComplete() {
        EdgarService edgar = mock(EdgarService.class);
        when(edgar.companyFacts("AAPL", null)).thenReturn(new CompanyFacts(Map.of()));
        SourceResult r = new EdgarFundamentalsSource(edgar).facts(Instrument.raw("AAPL"));
        assertThat(r.series(FundamentalConcept.LONG_TERM_DEBT).datapoints()).isEmpty();
        assertThat(r.semantics()).isEqualTo(AbsenceSemantics.COMPLETE);
    }

    @Test
    void cfoRecognizesContinuingOperationsTag() {
        // Ported from the deleted FundamentalScoreServiceTest (pre-router refactor, commit
        // 5241fcd): when the primary NetCashProvidedByUsedInOperatingActivities tag is absent,
        // the ContinuingOperations synonym must still be picked up wholesale.
        EdgarService edgar = mock(EdgarService.class);
        CompanyFacts f = new CompanyFacts(Map.of(
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations",
                new ConceptSeries("USD", List.of(dp("2025-09-30", 150), dp("2024-09-30", 100)))));
        when(edgar.companyFacts("CFOTAG", null)).thenReturn(f);

        EdgarFundamentalsSource src = new EdgarFundamentalsSource(edgar);
        SourceResult r = src.facts(Instrument.raw("CFOTAG"));

        assertThat(r.semantics()).isEqualTo(AbsenceSemantics.COMPLETE);
        ConceptSeries cfo = r.series(FundamentalConcept.OPERATING_CASH_FLOW);
        assertThat(cfo.datapoints()).extracting(ConceptDatapoint::value)
            .containsExactly(BigDecimal.valueOf(150.0), BigDecimal.valueOf(100.0));
    }
}
