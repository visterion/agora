package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.news.NewsItem;
import de.visterion.agora.fetch.news.FinnhubNewsProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetCompanyNewsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsNews() {
        FinnhubNewsProvider svc = Mockito.mock(FinnhubNewsProvider.class);
        when(svc.companyNews(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new NewsItem("Apple beats", "s", "Reuters", "news", Instant.ofEpochSecond(1749600000L), "http://x")));
        var tool = new GetCompanyNewsTool(svc);
        var r = tool.call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("news").get(0).get("headline").asString()).isEqualTo("Apple beats");
    }

    @Test void defaultsToLast7Days() {
        FinnhubNewsProvider svc = Mockito.mock(FinnhubNewsProvider.class);
        when(svc.companyNews(any(), any(LocalDate.class), any(LocalDate.class))).thenReturn(java.util.List.of());
        new GetCompanyNewsTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL"));
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(svc).companyNews(eq("AAPL"), from.capture(), to.capture());
        assertThat(to.getValue()).isEqualTo(LocalDate.now());
        assertThat(from.getValue()).isEqualTo(to.getValue().minusDays(7));
    }

    @Test void missingSymbolUnavailable() {
        assertThat(new GetCompanyNewsTool(Mockito.mock(FinnhubNewsProvider.class))
                .call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        FinnhubNewsProvider svc = Mockito.mock(FinnhubNewsProvider.class);
        when(svc.companyNews(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no key", null));
        assertThat(new GetCompanyNewsTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL")).available()).isFalse();
    }
}
