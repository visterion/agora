package de.visterion.agora.fetch.news;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Binds agora.data.news.* — feed list, result cap, and per-feed HTTP timeout. */
@Component
@ConfigurationProperties(prefix = "agora.data.news")
public class NewsFeedsProperties {

    /** Hard cap on merged items returned by the aggregator. */
    private int maxItems = 200;
    /** Per-feed HTTP read timeout in milliseconds. */
    private long feedTimeoutMs = 5000;
    /** Configured RSS/Atom feeds; empty list = Finnhub only. */
    private List<FeedConfig> feeds = new ArrayList<>();

    public int getMaxItems() { return maxItems; }
    public void setMaxItems(int maxItems) { this.maxItems = maxItems; }
    public long getFeedTimeoutMs() { return feedTimeoutMs; }
    public void setFeedTimeoutMs(long feedTimeoutMs) { this.feedTimeoutMs = feedTimeoutMs; }
    public List<FeedConfig> getFeeds() { return feeds; }
    public void setFeeds(List<FeedConfig> feeds) { this.feeds = feeds; }

    /** One configured feed: id, URL template (optional {symbol} placeholder), media-type label. */
    public static class FeedConfig {
        private String id;
        private String url;
        /** "news" (editorial/wire) or "social" (user-generated). */
        private String sourceType = "news";
        /** Per-feed HTTP User-Agent header; some hosts rate-limit or degrade generic UAs. */
        private String userAgent = "agora-news/1.0";
        /** Minimum spacing (ms) enforced on this feed's host after a successful call; 0 = no spacing. */
        private long minIntervalMs = 0;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        public long getMinIntervalMs() { return minIntervalMs; }
        public void setMinIntervalMs(long minIntervalMs) { this.minIntervalMs = minIntervalMs; }
    }
}
