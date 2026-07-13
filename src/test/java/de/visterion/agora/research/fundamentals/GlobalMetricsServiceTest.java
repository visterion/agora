package de.visterion.agora.research.fundamentals;
import de.visterion.agora.data.Instrument;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal; import java.time.LocalDate; import java.util.List; import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any; import static org.mockito.Mockito.*;

class GlobalMetricsServiceTest {
    private ConceptSeries s(String unit, double v){ return new ConceptSeries(unit,
        List.of(new ConceptDatapoint(null, LocalDate.parse("2025-12-31"), BigDecimal.valueOf(v),2025,"FY",null,LocalDate.parse("2025-12-31")))); }

    @Test void ratioMetricsAtPercentScale() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        Map<FundamentalConcept,ConceptSeries> c = Map.of(
            FundamentalConcept.NET_INCOME, s("EUR",100), FundamentalConcept.TOTAL_ASSETS, s("EUR",1000),
            FundamentalConcept.REVENUE, s("EUR",500), FundamentalConcept.GROSS_PROFIT, s("EUR",200),
            FundamentalConcept.CURRENT_ASSETS, s("EUR",300), FundamentalConcept.CURRENT_LIABILITIES, s("EUR",150),
            FundamentalConcept.TOTAL_LIABILITIES, s("EUR",400), FundamentalConcept.TOTAL_DEBT, s("EUR",300));
        when(router.facts(any())).thenReturn(new SourceResult(c, AbsenceSemantics.SPARSE));
        // OHLC/quote/FX deps null for this task — service tolerates and just omits those metrics.
        var svc = new GlobalMetricsService(router, null, null);
        var m = svc.metrics(Instrument.raw("SAP.DE")).metrics();
        assertThat(m.path("roaTTM").decimalValue()).isEqualByComparingTo("10");        // 100/1000*100
        assertThat(m.path("grossMarginTTM").decimalValue()).isEqualByComparingTo("40"); // 200/500*100
        assertThat(m.path("currentRatioQuarterly").decimalValue()).isEqualByComparingTo("2"); // 300/150
        assertThat(m.path("totalDebt/totalEquityQuarterly").decimalValue()).isEqualByComparingTo("0.5"); // 300/(1000-400)
    }
}
