package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.finnhub.EstimatesService;
import de.visterion.agora.fetch.finnhub.Recommendation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GetAnalystEstimatesToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsRecommendations() {
        EstimatesService svc = Mockito.mock(EstimatesService.class);
        when(svc.recommendations(any())).thenReturn(List.of(new Recommendation("2025-06-01", 10, 15, 5, 1, 0)));
        var r = new GetAnalystEstimatesTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("recommendations").get(0).get("strongBuy").asInt()).isEqualTo(10);
    }

    @Test void descriptionMentionsRecommendationCountsAndPointsToEarningsEstimates() {
        String description = new GetAnalystEstimatesTool(Mockito.mock(EstimatesService.class)).description();
        assertThat(description).containsIgnoringCase("recommendation");
        assertThat(description).contains("get_earnings_estimates");
    }

    @Test void missingSymbolUnavailable() {
        assertThat(new GetAnalystEstimatesTool(Mockito.mock(EstimatesService.class))
                .call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        EstimatesService svc = Mockito.mock(EstimatesService.class);
        when(svc.recommendations(any())).thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no key", null));
        assertThat(new GetAnalystEstimatesTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL")).available()).isFalse();
    }
}
