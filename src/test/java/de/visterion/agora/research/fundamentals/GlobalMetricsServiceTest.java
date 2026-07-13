package de.visterion.agora.research.fundamentals;
import de.visterion.agora.data.FxRate;
import de.visterion.agora.data.FxService;
import de.visterion.agora.data.Instrument;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.data.Quote;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal; import java.math.MathContext; import java.time.LocalDate; import java.util.List; import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any; import static org.mockito.ArgumentMatchers.anyInt; import static org.mockito.ArgumentMatchers.eq; import static org.mockito.Mockito.*;

class GlobalMetricsServiceTest {
    private ConceptSeries s(String unit, double v){ return new ConceptSeries(unit,
        List.of(new ConceptDatapoint(null, LocalDate.parse("2025-12-31"), BigDecimal.valueOf(v),2025,"FY",null,LocalDate.parse("2025-12-31")))); }
    private ConceptSeries s2(String unit, double cur, double prior){ return new ConceptSeries(unit,
        List.of(new ConceptDatapoint(null, LocalDate.parse("2025-12-31"), BigDecimal.valueOf(cur),2025,"FY",null,LocalDate.parse("2025-12-31")),
                new ConceptDatapoint(null, LocalDate.parse("2024-12-31"), BigDecimal.valueOf(prior),2024,"FY",null,LocalDate.parse("2024-12-31")))); }

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

    @Test void fiftyTwoWeekLowHighFromOhlc() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        Map<FundamentalConcept,ConceptSeries> c = Map.of(
            FundamentalConcept.NET_INCOME, s("EUR",100), FundamentalConcept.TOTAL_ASSETS, s("EUR",1000),
            FundamentalConcept.REVENUE, s("EUR",500), FundamentalConcept.GROSS_PROFIT, s("EUR",200),
            FundamentalConcept.CURRENT_ASSETS, s("EUR",300), FundamentalConcept.CURRENT_LIABILITIES, s("EUR",150),
            FundamentalConcept.TOTAL_LIABILITIES, s("EUR",400), FundamentalConcept.TOTAL_DEBT, s("EUR",300));
        when(router.facts(any())).thenReturn(new SourceResult(c, AbsenceSemantics.SPARSE));

        MarketDataService marketData = mock(MarketDataService.class);
        LocalDate d = LocalDate.parse("2025-12-31");
        List<OhlcBar> bars = List.of(
            new OhlcBar(d, BigDecimal.valueOf(9.5), BigDecimal.valueOf(10.5), BigDecimal.valueOf(9), BigDecimal.valueOf(10), 1000000),
            new OhlcBar(d, BigDecimal.valueOf(24), BigDecimal.valueOf(26), BigDecimal.valueOf(24), BigDecimal.valueOf(25), 1200000),
            new OhlcBar(d, BigDecimal.valueOf(6.5), BigDecimal.valueOf(8), BigDecimal.valueOf(6), BigDecimal.valueOf(7), 900000),
            new OhlcBar(d, BigDecimal.valueOf(17), BigDecimal.valueOf(19), BigDecimal.valueOf(16), BigDecimal.valueOf(18), 1100000));
        when(marketData.ohlc(eq("SAP.DE"), anyInt())).thenReturn(bars);

        var svc = new GlobalMetricsService(router, marketData, null);
        var m = svc.metrics(Instrument.raw("SAP.DE")).metrics();
        assertThat(m.path("52WeekLow").decimalValue()).isEqualByComparingTo("7");
        assertThat(m.path("52WeekHigh").decimalValue()).isEqualByComparingTo("25");
    }

    @Test void freeCashFlowPerShareTTMConvertedToQuoteCurrency() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        Map<FundamentalConcept,ConceptSeries> c = Map.of(
            FundamentalConcept.TOTAL_ASSETS, s("CNY",1000), FundamentalConcept.TOTAL_LIABILITIES, s("CNY",400),
            FundamentalConcept.OPERATING_CASH_FLOW, s("CNY",1000), FundamentalConcept.SHARES_OUTSTANDING, s("CNY",100));
        when(router.facts(any())).thenReturn(new SourceResult(c, AbsenceSemantics.SPARSE));

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.quote("0700.HK")).thenReturn(new Quote("0700.HK", BigDecimal.TEN, BigDecimal.ZERO, "HKD"));
        FxService fx = mock(FxService.class);
        when(fx.rate("CNY","HKD")).thenReturn(new FxRate("CNY","HKD", BigDecimal.valueOf(1.1)));
        when(fx.rate("HKD","CNY")).thenReturn(new FxRate("HKD","CNY", BigDecimal.valueOf(0.9)));

        var svc = new GlobalMetricsService(router, marketData, fx);
        var m = svc.metrics(Instrument.raw("0700.HK")).metrics();
        assertThat(m.path("freeCashFlowPerShareTTM").decimalValue()).isEqualByComparingTo("11");
    }

    @Test void freeCashFlowPerShareTTMOmittedWhenFxMissing() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        Map<FundamentalConcept,ConceptSeries> c = Map.of(
            FundamentalConcept.TOTAL_ASSETS, s("CNY",1000), FundamentalConcept.TOTAL_LIABILITIES, s("CNY",400),
            FundamentalConcept.OPERATING_CASH_FLOW, s("CNY",1000), FundamentalConcept.SHARES_OUTSTANDING, s("CNY",100));
        when(router.facts(any())).thenReturn(new SourceResult(c, AbsenceSemantics.SPARSE));

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.quote("0700.HK")).thenReturn(new Quote("0700.HK", BigDecimal.TEN, BigDecimal.ZERO, "HKD"));
        FxService fx = mock(FxService.class);
        when(fx.rate("CNY","HKD")).thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no rate", null));
        when(fx.rate("HKD","CNY")).thenReturn(new FxRate("HKD","CNY", BigDecimal.valueOf(0.9)));

        var svc = new GlobalMetricsService(router, marketData, fx);
        var m = svc.metrics(Instrument.raw("0700.HK")).metrics();
        assertThat(m.has("freeCashFlowPerShareTTM")).isFalse();
    }

    @Test void marketCapPbPeInReportingCurrency() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        Map<FundamentalConcept,ConceptSeries> c = Map.of(
            FundamentalConcept.TOTAL_ASSETS, s("CNY",1000), FundamentalConcept.TOTAL_LIABILITIES, s("CNY",400),
            FundamentalConcept.NET_INCOME, s("CNY",100), FundamentalConcept.SHARES_OUTSTANDING, s("CNY",100));
        when(router.facts(any())).thenReturn(new SourceResult(c, AbsenceSemantics.SPARSE));

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.quote("0700.HK")).thenReturn(new Quote("0700.HK", BigDecimal.valueOf(50), BigDecimal.ZERO, "HKD"));
        FxService fx = mock(FxService.class);
        when(fx.rate("HKD","CNY")).thenReturn(new FxRate("HKD","CNY", BigDecimal.ONE));

        var svc = new GlobalMetricsService(router, marketData, fx);
        var m = svc.metrics(Instrument.raw("0700.HK")).metrics();
        assertThat(m.path("marketCapitalization").decimalValue()).isEqualByComparingTo("0.005"); // 5000/1e6
        assertThat(m.path("pbAnnual").decimalValue()).isEqualByComparingTo(
            BigDecimal.valueOf(5000).divide(BigDecimal.valueOf(600), MathContext.DECIMAL64)); // 5000/(1000-400)
        assertThat(m.path("peTTM").decimalValue()).isEqualByComparingTo("50"); // 5000/100
    }

    @Test void marketCapPbPeOmittedWhenFxMissing() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        Map<FundamentalConcept,ConceptSeries> c = Map.of(
            FundamentalConcept.TOTAL_ASSETS, s("CNY",1000), FundamentalConcept.TOTAL_LIABILITIES, s("CNY",400),
            FundamentalConcept.NET_INCOME, s("CNY",100), FundamentalConcept.SHARES_OUTSTANDING, s("CNY",100));
        when(router.facts(any())).thenReturn(new SourceResult(c, AbsenceSemantics.SPARSE));

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.quote("0700.HK")).thenReturn(new Quote("0700.HK", BigDecimal.valueOf(50), BigDecimal.ZERO, "HKD"));
        FxService fx = mock(FxService.class);
        when(fx.rate("HKD","CNY")).thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no rate", null));

        var svc = new GlobalMetricsService(router, marketData, fx);
        var m = svc.metrics(Instrument.raw("0700.HK")).metrics();
        assertThat(m.has("marketCapitalization")).isFalse();
        assertThat(m.has("pbAnnual")).isFalse();
        assertThat(m.has("peTTM")).isFalse();
    }

    @Test void revenueGrowthTTMYoyFromTwoLatestPoints() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        Map<FundamentalConcept,ConceptSeries> c = Map.of(
            FundamentalConcept.REVENUE, s2("EUR",550,500));
        when(router.facts(any())).thenReturn(new SourceResult(c, AbsenceSemantics.SPARSE));

        var svc = new GlobalMetricsService(router, null, null);
        var m = svc.metrics(Instrument.raw("SAP.DE")).metrics();
        assertThat(m.path("revenueGrowthTTMYoy").decimalValue()).isEqualByComparingTo("10");
    }

    @Test void revenueGrowthTTMYoyOmittedWhenPriorNonPositive() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        Map<FundamentalConcept,ConceptSeries> c = Map.of(
            FundamentalConcept.REVENUE, s2("EUR",550,0));
        when(router.facts(any())).thenReturn(new SourceResult(c, AbsenceSemantics.SPARSE));

        var svc = new GlobalMetricsService(router, null, null);
        var m = svc.metrics(Instrument.raw("SAP.DE")).metrics();
        assertThat(m.has("revenueGrowthTTMYoy")).isFalse();
    }

    @Test void revenueGrowthTTMYoyOmittedWhenOnlyOnePoint() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        Map<FundamentalConcept,ConceptSeries> c = Map.of(
            FundamentalConcept.REVENUE, s("EUR",500));
        when(router.facts(any())).thenReturn(new SourceResult(c, AbsenceSemantics.SPARSE));

        var svc = new GlobalMetricsService(router, null, null);
        var m = svc.metrics(Instrument.raw("SAP.DE")).metrics();
        assertThat(m.has("revenueGrowthTTMYoy")).isFalse();
    }

    @Test void epsGrowthTTMYoyFromNetIncomeAndShares() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        Map<FundamentalConcept,ConceptSeries> c = Map.of(
            FundamentalConcept.NET_INCOME, s2("EUR",110,100),
            FundamentalConcept.SHARES_OUTSTANDING, s2("EUR",10,10));
        when(router.facts(any())).thenReturn(new SourceResult(c, AbsenceSemantics.SPARSE));

        var svc = new GlobalMetricsService(router, null, null);
        var m = svc.metrics(Instrument.raw("SAP.DE")).metrics();
        assertThat(m.path("epsGrowthTTMYoy").decimalValue()).isEqualByComparingTo("10");
    }
}
