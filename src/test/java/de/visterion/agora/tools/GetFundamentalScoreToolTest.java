package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.research.fundamentals.FundamentalScoreService;
import de.visterion.agora.research.fundamentals.PiotroskiFScore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class GetFundamentalScoreToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsFundamentalScore() {
        Map<String, PiotroskiFScore.Criterion> criteria = new LinkedHashMap<>();
        criteria.put("roaPositive", new PiotroskiFScore.Criterion(true, true));
        criteria.put("noNewShares", new PiotroskiFScore.Criterion(false, true));
        Map<String, BigDecimal> raw = new LinkedHashMap<>();
        raw.put("roa", new BigDecimal("0.12"));
        PiotroskiFScore score = new PiotroskiFScore(7, 8, criteria, raw);

        FundamentalScoreService svc = Mockito.mock(FundamentalScoreService.class);
        when(svc.piotroski(anyString())).thenReturn(score);

        var r = new GetFundamentalScoreTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        var out = r.output();
        assertThat(out.get("symbol").asString()).isEqualTo("AAPL");
        var piotroskiF = out.get("scores").get("piotroskiF");
        assertThat(piotroskiF.get("score").asInt()).isEqualTo(7);
        assertThat(piotroskiF.get("criteriaAvailable").asInt()).isEqualTo(8);
        var roaPositive = piotroskiF.get("criteria").get("roaPositive");
        assertThat(roaPositive.get("met").asBoolean()).isTrue();
        assertThat(roaPositive.get("available").asBoolean()).isTrue();
        assertThat(piotroskiF.get("criteria").get("noNewShares").get("met").asBoolean()).isFalse();
        assertThat(piotroskiF.get("raw").get("roa").decimalValue()).isEqualByComparingTo("0.12");
    }

    @Test void blankSymbolUnavailable() {
        var r = new GetFundamentalScoreTool(Mockito.mock(FundamentalScoreService.class))
                .call(mapper.createObjectNode().put("symbol", ""));
        assertThat(r.available()).isFalse();
    }

    @Test void marketDataExceptionUnavailable() {
        FundamentalScoreService svc = Mockito.mock(FundamentalScoreService.class);
        when(svc.piotroski(anyString()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR down", null));
        var r = new GetFundamentalScoreTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isFalse();
    }

    @Test void missingSymbolUnavailable() {
        var r = new GetFundamentalScoreTool(Mockito.mock(FundamentalScoreService.class))
                .call(mapper.createObjectNode());
        assertThat(r.available()).isFalse();
    }

    @Test void nameIsGetFundamentalScore() {
        assertThat(new GetFundamentalScoreTool(Mockito.mock(FundamentalScoreService.class)).name())
                .isEqualTo("get_fundamental_score");
    }

    @Test void namespaceIsGeneral() {
        assertThat(new GetFundamentalScoreTool(Mockito.mock(FundamentalScoreService.class)).namespace())
                .isEqualTo("general");
    }
}
