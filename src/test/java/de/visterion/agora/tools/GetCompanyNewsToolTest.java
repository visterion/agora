package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.news.NewsAggregator;
import de.visterion.agora.fetch.news.NewsAggregator.AggregatedNews;
import de.visterion.agora.fetch.news.NewsItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetCompanyNewsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static NewsItem newsItem(Instant dt) {
        return new NewsItem("Apple beats", "s", "Reuters", "news", dt, "http://x");
    }

    private static NewsAggregator mockAggregator(AggregatedNews result) {
        NewsAggregator agg = Mockito.mock(NewsAggregator.class);
        when(agg.aggregate(any(), any(LocalDate.class), any(LocalDate.class), anySet()))
                .thenReturn(result);
        return agg;
    }

    @Test void returnsNews() {
        var tool = new GetCompanyNewsTool(mockAggregator(
                new AggregatedNews(List.of(newsItem(Instant.ofEpochSecond(1749600000L))), List.of())));
        var r = tool.call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("news").get(0).get("headline").asString()).isEqualTo("Apple beats");
    }

    @Test void serializesSourceTypePerItem() {
        var tool = new GetCompanyNewsTool(mockAggregator(
                new AggregatedNews(List.of(newsItem(Instant.ofEpochSecond(1749600000L))), List.of())));
        var r = tool.call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.output().get("news").get(0).get("sourceType").asString()).isEqualTo("news");
    }

    @Test void nullDatetimeSerializesAsJsonNullNotNpe() {
        // Regression: the old tool NPE'd on items without datetime.
        var tool = new GetCompanyNewsTool(mockAggregator(
                new AggregatedNews(List.of(newsItem(null)), List.of())));
        var r = tool.call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("news").get(0).get("datetime").isNull()).isTrue();
    }

    @Test void warningsOmittedWhenEmpty() {
        var tool = new GetCompanyNewsTool(mockAggregator(
                new AggregatedNews(List.of(newsItem(Instant.ofEpochSecond(1L))), List.of())));
        var r = tool.call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.output().has("warnings")).isFalse();
    }

    @Test void warningsSerializedWhenPresent() {
        var tool = new GetCompanyNewsTool(mockAggregator(
                new AggregatedNews(List.of(), List.of("rss:yahoo-rss: timeout"))));
        var r = tool.call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.output().get("warnings").get(0).asString()).isEqualTo("rss:yahoo-rss: timeout");
    }

    @Test void passesSourceTypesInputToAggregator() {
        NewsAggregator agg = mockAggregator(new AggregatedNews(List.of(), List.of()));
        var args = mapper.createObjectNode().put("symbol", "AAPL");
        args.putArray("sourceTypes").add("news");
        new GetCompanyNewsTool(agg).call(args);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> types = ArgumentCaptor.forClass(Set.class);
        verify(agg).aggregate(eq("AAPL"), any(LocalDate.class), any(LocalDate.class), types.capture());
        assertThat(types.getValue()).containsExactly("news");
    }

    @Test void defaultsToLast7Days() {
        NewsAggregator agg = mockAggregator(new AggregatedNews(List.of(), List.of()));
        new GetCompanyNewsTool(agg).call(mapper.createObjectNode().put("symbol", "AAPL"));
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(agg).aggregate(eq("AAPL"), from.capture(), to.capture(), anySet());
        assertThat(to.getValue()).isEqualTo(LocalDate.now());
        assertThat(from.getValue()).isEqualTo(to.getValue().minusDays(7));
    }

    @Test void missingSymbolUnavailable() {
        assertThat(new GetCompanyNewsTool(Mockito.mock(NewsAggregator.class))
                .call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void aggregatorExceptionUnavailable() {
        NewsAggregator agg = Mockito.mock(NewsAggregator.class);
        when(agg.aggregate(any(), any(LocalDate.class), any(LocalDate.class), anySet()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no key", null));
        assertThat(new GetCompanyNewsTool(agg)
                .call(mapper.createObjectNode().put("symbol", "AAPL")).available()).isFalse();
    }

    @Test void inputSchemaDocumentsSourceTypes() {
        var schema = new GetCompanyNewsTool(Mockito.mock(NewsAggregator.class)).inputSchema();
        assertThat(schema.get("properties").has("sourceTypes")).isTrue();
        assertThat(schema.get("properties").get("sourceTypes").get("description").asString())
                .contains("news").contains("social");
    }

    @Test void serializesDomainPerItem() {
        var withDomain = new NewsItem("Apple beats", "s", "Reuters", "news",
                Instant.ofEpochSecond(1749600000L), "https://www.reuters.com/x", "reuters.com");
        var tool = new GetCompanyNewsTool(mockAggregator(
                new AggregatedNews(List.of(withDomain), List.of())));
        var r = tool.call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.output().get("news").get(0).get("domain").asString()).isEqualTo("reuters.com");
    }

    @Test void nullDomainSerializesAsExplicitJsonNull() {
        var tool = new GetCompanyNewsTool(mockAggregator(
                new AggregatedNews(List.of(newsItem(Instant.ofEpochSecond(1L))), List.of())));
        var r = tool.call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.output().get("news").get(0).has("domain")).isTrue();
        assertThat(r.output().get("news").get(0).get("domain").isNull()).isTrue();
    }

    @Test void descriptionDocumentsDomainField() {
        assertThat(new GetCompanyNewsTool(Mockito.mock(NewsAggregator.class)).description())
                .contains("domain");
    }
}
