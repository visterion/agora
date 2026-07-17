package de.visterion.agora.fetch.news;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the news provider chain: finnhub first, then one {@link RssNewsProvider}
 * INSTANCE per configured feed, in config order (order = dedup priority).
 */
@Configuration
public class NewsConfiguration {

    @Bean
    public NewsAggregator newsAggregator(FinnhubNewsProvider finnhub,
                                         NewsFeedsProperties props,
                                         @Value("${agora.data.cache.ttl.news-seconds:900}") long ttlSeconds) {
        List<NewsProvider> chain = new ArrayList<>();
        chain.add(finnhub);
        for (NewsFeedsProperties.FeedConfig feed : props.getFeeds()) {
            chain.add(new RssNewsProvider(feed.getId(), feed.getUrl(), feed.getSourceType(),
                    props.getFeedTimeoutMs(), ttlSeconds, System::currentTimeMillis,
                    feed.getUserAgent(), feed.getMinIntervalMs()));
        }
        return new NewsAggregator(chain, props.getMaxItems());
    }
}
