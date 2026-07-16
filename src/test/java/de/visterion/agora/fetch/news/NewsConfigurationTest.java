package de.visterion.agora.fetch.news;

import de.visterion.agora.fetch.finnhub.FinnhubClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewsConfigurationTest {

    @Test void buildsAggregatorWithFinnhubFirstThenFeedsInConfigOrder() {
        NewsFeedsProperties props = new NewsFeedsProperties();
        NewsFeedsProperties.FeedConfig yahoo = new NewsFeedsProperties.FeedConfig();
        yahoo.setId("yahoo-rss");
        yahoo.setUrl("https://feeds.example.com/rss?s={symbol}");
        yahoo.setSourceType("news");
        NewsFeedsProperties.FeedConfig reddit = new NewsFeedsProperties.FeedConfig();
        reddit.setId("reddit-stocks");
        reddit.setUrl("https://social.example.com/search.rss?q={symbol}");
        reddit.setSourceType("social");
        props.setFeeds(List.of(yahoo, reddit));

        FinnhubNewsProvider finnhub = new FinnhubNewsProvider(
                new FinnhubClient(RestClient.builder().build(), "k"), 900L, System::currentTimeMillis);
        NewsAggregator agg = new NewsConfiguration().newsAggregator(finnhub, props, 900L);

        assertThat(agg.providers()).extracting(NewsProvider::id)
                .containsExactly("finnhub", "rss:yahoo-rss", "rss:reddit-stocks");
    }

    @Test void emptyFeedListYieldsFinnhubOnly() {
        FinnhubNewsProvider finnhub = new FinnhubNewsProvider(
                new FinnhubClient(RestClient.builder().build(), "k"), 900L, System::currentTimeMillis);
        NewsAggregator agg = new NewsConfiguration()
                .newsAggregator(finnhub, new NewsFeedsProperties(), 900L);
        assertThat(agg.providers()).extracting(NewsProvider::id).containsExactly("finnhub");
    }
}
