package de.visterion.agora.fetch.news;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NewsFeedsPropertiesTest {

    private NewsFeedsProperties bind(Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props))
                .bind("agora.data.news", Bindable.of(NewsFeedsProperties.class))
                .orElseGet(NewsFeedsProperties::new);
    }

    @Test void bindsFeedsIncludingKebabCaseSourceType() {
        NewsFeedsProperties p = bind(Map.of(
                "agora.data.news.max-items", "150",
                "agora.data.news.feed-timeout-ms", "3000",
                "agora.data.news.feeds[0].id", "yahoo-rss",
                "agora.data.news.feeds[0].url", "https://feeds.example.com/rss?s={symbol}",
                "agora.data.news.feeds[0].source-type", "news",
                "agora.data.news.feeds[1].id", "reddit-stocks",
                "agora.data.news.feeds[1].url", "https://social.example.com/search.rss?q={symbol}",
                "agora.data.news.feeds[1].source-type", "social"));
        assertThat(p.getMaxItems()).isEqualTo(150);
        assertThat(p.getFeedTimeoutMs()).isEqualTo(3000L);
        assertThat(p.getFeeds()).hasSize(2);
        assertThat(p.getFeeds().get(0).getId()).isEqualTo("yahoo-rss");
        assertThat(p.getFeeds().get(0).getSourceType()).isEqualTo("news");
        assertThat(p.getFeeds().get(1).getSourceType()).isEqualTo("social");
    }

    @Test void defaultsWhenUnset() {
        NewsFeedsProperties p = bind(Map.of());
        assertThat(p.getMaxItems()).isEqualTo(200);
        assertThat(p.getFeedTimeoutMs()).isEqualTo(5000L);
        assertThat(p.getFeeds()).isEmpty();
    }

    @Test void feedSourceTypeDefaultsToNews() {
        NewsFeedsProperties p = bind(Map.of(
                "agora.data.news.feeds[0].id", "wire",
                "agora.data.news.feeds[0].url", "https://feeds.example.com/all.rss"));
        assertThat(p.getFeeds().get(0).getSourceType()).isEqualTo("news");
    }
}
