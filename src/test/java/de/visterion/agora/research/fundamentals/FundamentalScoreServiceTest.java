package de.visterion.agora.research.fundamentals;

import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.EdgarService.CompanyFacts;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import de.visterion.agora.fetch.split.SplitEvent;
import de.visterion.agora.fetch.split.SplitService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FundamentalScoreServiceTest {

    // Annual duration: fiscal year t = 2024, t-1 = 2023.
    private static final LocalDate T_START = LocalDate.of(2024, 1, 1);
    private static final LocalDate T_END = LocalDate.of(2024, 12, 31);
    private static final LocalDate P_START = LocalDate.of(2023, 1, 1);
    private static final LocalDate P_END = LocalDate.of(2023, 12, 31);

    // Instants: point-in-time at fiscal year end.
    private static final LocalDate T_INSTANT = LocalDate.of(2024, 12, 31);
    private static final LocalDate P_INSTANT = LocalDate.of(2023, 12, 31);

    private static ConceptSeries duration(double t, double p) {
        return new ConceptSeries("USD", List.of(
                new ConceptDatapoint(T_START, T_END, BigDecimal.valueOf(t), 2024, "FY", "10-K", T_END),
                new ConceptDatapoint(P_START, P_END, BigDecimal.valueOf(p), 2023, "FY", "10-K", P_END)));
    }

    private static ConceptSeries instant(double t, double p) {
        return new ConceptSeries("USD", List.of(
                new ConceptDatapoint(null, T_INSTANT, BigDecimal.valueOf(t), 2024, "FY", "10-K", T_INSTANT),
                new ConceptDatapoint(null, P_INSTANT, BigDecimal.valueOf(p), 2023, "FY", "10-K", P_INSTANT)));
    }

    private static SplitService noSplits() {
        SplitService s = mock(SplitService.class);
        when(s.splits(anyString())).thenReturn(List.of());
        return s;
    }

    private static Map<String, ConceptSeries> cleanImprovingCompanyFacts() {
        Map<String, ConceptSeries> byTag = new HashMap<>();
        byTag.put("NetIncomeLoss", duration(120, 80));
        byTag.put("NetCashProvidedByUsedInOperatingActivities", duration(150, 100));
        byTag.put("Assets", instant(1000, 1000));
        byTag.put("LongTermDebtNoncurrent", instant(100, 200));
        byTag.put("AssetsCurrent", instant(500, 400));
        byTag.put("LiabilitiesCurrent", instant(200, 200));
        byTag.put("CommonStockSharesOutstanding", instant(1000, 1000));
        byTag.put("GrossProfit", duration(400, 300));
        byTag.put("Revenues", duration(800, 700));
        return byTag;
    }

    @Test
    void allNineCriteriaMetAndAvailable() {
        // ROA: 120/1000=0.12 > 0 -> roaPositive met.
        // CFO: 150 > 0 -> cfoPositive met.
        // ROA improved: 0.12 > 80/1000=0.08 -> roaImproved met.
        // CFO 150 > NI 120 -> cfoExceedsNetIncome met.
        // Leverage: 100/1000=0.10 < 200/1000=0.20 -> leverageDecreased met.
        // Current ratio: 500/200=2.5 > 400/200=2.0 -> currentRatioImproved met.
        // Shares: 1000 <= 1000 -> noNewShares met.
        // Gross margin: 400/800=0.5 > 300/700=0.4286 -> grossMarginImproved met.
        // Asset turnover: 800/1000=0.8 > 700/1000=0.7 -> assetTurnoverImproved met.
        EdgarService edgar = mock(EdgarService.class);
        when(edgar.companyFacts(anyString(), any())).thenReturn(new CompanyFacts(cleanImprovingCompanyFacts()));
        FundamentalScoreService service = new FundamentalScoreService(edgar, noSplits());

        PiotroskiFScore result = service.piotroski("CLEAN");

        assertThat(result.score()).isEqualTo(9);
        assertThat(result.criteriaAvailable()).isEqualTo(9);
        assertThat(result.criteria().get("roaPositive").met()).isTrue();
        assertThat(result.criteria().get("roaPositive").available()).isTrue();
        assertThat(result.criteria().get("cfoExceedsNetIncome").met()).isTrue();
        assertThat(result.criteria().get("currentRatioImproved").met()).isTrue();
        assertThat(result.criteria().get("grossMarginImproved").met()).isTrue();
        assertThat(result.criteria().get("assetTurnoverImproved").met()).isTrue();
        assertThat(result.raw().get("roa")).isEqualByComparingTo("0.12");
    }

    @Test
    void missingCurrentLiabilitiesMakesCurrentRatioCriterionUnavailable() {
        Map<String, ConceptSeries> byTag = cleanImprovingCompanyFacts();
        byTag.remove("LiabilitiesCurrent");
        EdgarService edgar = mock(EdgarService.class);
        when(edgar.companyFacts(anyString(), any())).thenReturn(new CompanyFacts(byTag));
        FundamentalScoreService service = new FundamentalScoreService(edgar, noSplits());

        PiotroskiFScore result = service.piotroski("PARTIAL");

        assertThat(result.criteria().get("currentRatioImproved").available()).isFalse();
        assertThat(result.criteria().get("currentRatioImproved").met()).isFalse();
        assertThat(result.criteriaAvailable()).isEqualTo(8);
        assertThat(result.score()).isEqualTo(8);
    }

    @Test
    void mixedFiscalYearsExcludeCrossConceptCriteriaInsteadOfMiscomputing() {
        // Assets series is shifted by one year relative to everything else (2023/2022 instead
        // of 2024/2023). Every criterion that cross-references Assets must be excluded (null),
        // not silently computed against the wrong fiscal year.
        Map<String, ConceptSeries> byTag = cleanImprovingCompanyFacts();
        byTag.put("Assets", new ConceptSeries("USD", List.of(
                new ConceptDatapoint(null, LocalDate.of(2023, 12, 31), BigDecimal.valueOf(1000), 2023, "FY", "10-K", null),
                new ConceptDatapoint(null, LocalDate.of(2022, 12, 31), BigDecimal.valueOf(1000), 2022, "FY", "10-K", null))));
        EdgarService edgar = mock(EdgarService.class);
        when(edgar.companyFacts(anyString(), any())).thenReturn(new CompanyFacts(byTag));
        FundamentalScoreService service = new FundamentalScoreService(edgar, noSplits());

        PiotroskiFScore result = service.piotroski("MIXEDYEAR");

        assertThat(result.criteria().get("roaImproved").available()).isFalse();
        assertThat(result.criteria().get("leverageDecreased").available()).isFalse();
        assertThat(result.criteria().get("assetTurnoverImproved").available()).isFalse();
        assertThat(result.raw()).doesNotContainKey("roa");
        assertThat(result.raw()).doesNotContainKey("assetTurnover");
    }

    @Test
    void debtFreeCompanyScoresLeveragePointInsteadOfBeingDocked() {
        Map<String, ConceptSeries> byTag = cleanImprovingCompanyFacts();
        byTag.remove("LongTermDebtNoncurrent");
        EdgarService edgar = mock(EdgarService.class);
        when(edgar.companyFacts(anyString(), any())).thenReturn(new CompanyFacts(byTag));
        FundamentalScoreService service = new FundamentalScoreService(edgar, noSplits());

        PiotroskiFScore result = service.piotroski("DEBTFREE");

        assertThat(result.criteria().get("leverageDecreased").available()).isTrue();
        assertThat(result.criteria().get("leverageDecreased").met()).isTrue();
    }

    @Test
    void roaPositiveEvaluatesFromNetIncomeSignAloneWhenAssetsMissing() {
        Map<String, ConceptSeries> byTag = cleanImprovingCompanyFacts();
        byTag.remove("Assets");
        EdgarService edgar = mock(EdgarService.class);
        when(edgar.companyFacts(anyString(), any())).thenReturn(new CompanyFacts(byTag));
        FundamentalScoreService service = new FundamentalScoreService(edgar, noSplits());

        PiotroskiFScore result = service.piotroski("NIONLY");

        assertThat(result.criteria().get("roaPositive").available()).isTrue();
        assertThat(result.criteria().get("roaPositive").met()).isTrue();
    }

    @Test
    void noNewSharesUsesSplitAdjustedShareCountsAndDoesNotFlagAStockSplitAsIssuance() {
        // Reported (unadjusted) shares double from 1000 to 2000 purely due to a 2:1 split that
        // happened between the two fiscal year-ends — not real dilution.
        Map<String, ConceptSeries> byTag = cleanImprovingCompanyFacts();
        byTag.put("CommonStockSharesOutstanding", instant(2000, 1000));
        EdgarService edgar = mock(EdgarService.class);
        when(edgar.companyFacts(anyString(), any())).thenReturn(new CompanyFacts(byTag));
        SplitService splitService = mock(SplitService.class);
        when(splitService.splits(anyString())).thenReturn(List.of(
                new SplitEvent(LocalDate.of(2024, 6, 1), BigDecimal.ONE, BigDecimal.valueOf(2))));
        FundamentalScoreService service = new FundamentalScoreService(edgar, splitService);

        PiotroskiFScore result = service.piotroski("SPLIT");

        assertThat(result.criteria().get("noNewShares").available()).isTrue();
        assertThat(result.criteria().get("noNewShares").met()).isTrue();
    }

    @Test
    void noNewSharesMarkedNullWhenSplitDataUnavailable() {
        Map<String, ConceptSeries> byTag = cleanImprovingCompanyFacts();
        EdgarService edgar = mock(EdgarService.class);
        when(edgar.companyFacts(anyString(), any())).thenReturn(new CompanyFacts(byTag));
        SplitService splitService = mock(SplitService.class);
        when(splitService.splits(anyString())).thenThrow(new RuntimeException("split provider down"));
        FundamentalScoreService service = new FundamentalScoreService(edgar, splitService);

        PiotroskiFScore result = service.piotroski("NOSPLITDATA");

        assertThat(result.criteria().get("noNewShares").available()).isFalse();
    }

    @Test
    void cfoRecognizesContinuingOperationsTag() {
        Map<String, ConceptSeries> byTag = cleanImprovingCompanyFacts();
        byTag.remove("NetCashProvidedByUsedInOperatingActivities");
        byTag.put("NetCashProvidedByUsedInOperatingActivitiesContinuingOperations", duration(150, 100));
        EdgarService edgar = mock(EdgarService.class);
        when(edgar.companyFacts(anyString(), any())).thenReturn(new CompanyFacts(byTag));
        FundamentalScoreService service = new FundamentalScoreService(edgar, noSplits());

        PiotroskiFScore result = service.piotroski("CFOTAG");

        assertThat(result.criteria().get("cfoPositive").available()).isTrue();
        assertThat(result.criteria().get("cfoPositive").met()).isTrue();
        assertThat(result.raw().get("cfo")).isEqualByComparingTo("150");
    }
}
