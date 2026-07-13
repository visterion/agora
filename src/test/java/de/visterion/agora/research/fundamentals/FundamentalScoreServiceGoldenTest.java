package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;
import de.visterion.agora.data.InstrumentResolver;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import de.visterion.agora.fetch.split.SplitService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FundamentalScoreServiceGoldenTest {
    private ConceptDatapoint inst(String end, double v, String filed) {
        return new ConceptDatapoint(null, LocalDate.parse(end), BigDecimal.valueOf(v), null, "FY", "10-K", LocalDate.parse(filed));
    }
    private ConceptDatapoint dur(String start, String end, double v) {
        return new ConceptDatapoint(LocalDate.parse(start), LocalDate.parse(end), BigDecimal.valueOf(v), null, "FY", "10-K", LocalDate.parse(end));
    }

    @Test
    void completeSourceScoresAndRestatementDedupWins() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        InstrumentResolver resolver = mock(InstrumentResolver.class);
        SplitService splits = mock(SplitService.class);
        when(resolver.resolve("AAPL")).thenReturn(Instrument.raw("AAPL"));
        when(splits.splits("AAPL")).thenReturn(List.of());
        // NI current > 0 -> roaPositive met; restatement: same periodEnd, later filed wins.
        Map<FundamentalConcept, ConceptSeries> c = Map.of(
            FundamentalConcept.NET_INCOME, new ConceptSeries("USD",
                List.of(dur("2024-10-01","2025-09-30", 100), dur("2023-10-01","2024-09-30", 90))),
            FundamentalConcept.TOTAL_ASSETS, new ConceptSeries("USD",
                List.of(inst("2025-09-30", 1000, "2025-11-01"), inst("2025-09-30", 1001, "2025-12-01"), inst("2024-09-30", 900, "2024-11-01"))));
        when(router.facts(any())).thenReturn(new SourceResult(c, AbsenceSemantics.COMPLETE));

        var svc = new FundamentalScoreService(router, resolver, splits);
        PiotroskiFScore s = svc.piotroski("AAPL");

        assertThat(s.criteria().get("roaPositive").met()).isTrue();
        // restated TotalAssets 1001 (later filed) is the current value used in roa raw:
        assertThat(s.raw().get("roa")).isEqualByComparingTo(BigDecimal.valueOf(100).divide(BigDecimal.valueOf(1001), java.math.MathContext.DECIMAL64));
        // debt-free shortcut fires under COMPLETE (no LONG_TERM_DEBT concept):
        assertThat(s.criteria().get("leverageDecreased").available()).isTrue();
        assertThat(s.criteria().get("leverageDecreased").met()).isTrue();
    }

    @Test
    void sparseSourceWithAbsentDebtLeavesLeverageUnavailable() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        InstrumentResolver resolver = mock(InstrumentResolver.class);
        SplitService splits = mock(SplitService.class);
        when(resolver.resolve("SAP.DE")).thenReturn(Instrument.raw("SAP.DE"));
        when(splits.splits("SAP.DE")).thenReturn(List.of());
        when(router.facts(any())).thenReturn(new SourceResult(Map.of(), AbsenceSemantics.SPARSE));
        var svc = new FundamentalScoreService(router, resolver, splits);
        PiotroskiFScore s = svc.piotroski("SAP.DE");
        assertThat(s.criteria().get("leverageDecreased").available()).isFalse(); // NOT a free point
        assertThat(s.criteria().get("noNewShares").available()).isFalse();       // empty split under SPARSE
    }
}
