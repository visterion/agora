package de.visterion.agora.research.fundamentals;

import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.EdgarService.CompanyFacts;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
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
        FundamentalScoreService service = new FundamentalScoreService(edgar);

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
        FundamentalScoreService service = new FundamentalScoreService(edgar);

        PiotroskiFScore result = service.piotroski("PARTIAL");

        assertThat(result.criteria().get("currentRatioImproved").available()).isFalse();
        assertThat(result.criteria().get("currentRatioImproved").met()).isFalse();
        assertThat(result.criteriaAvailable()).isEqualTo(8);
        assertThat(result.score()).isEqualTo(8);
    }
}
