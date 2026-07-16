package de.visterion.agora.fetch.news;

import java.time.LocalDate;
import java.util.List;

/** One pluggable company-news source. Mirrors the MarketDataProvider plugin pattern. */
public interface NewsProvider {

    /** Stable provider id, e.g. {@code "finnhub"} or {@code "rss:yahoo-rss"}. */
    String id();

    /** True when this provider has everything it needs (keys, URL) to serve requests. */
    boolean configured();

    /**
     * Headlines for {@code symbol} in the closed date window {@code [from, to]}.
     *
     * @throws de.visterion.agora.data.MarketDataException on provider failure;
     *         the aggregator catches per provider and degrades to a partial result.
     */
    List<NewsItem> companyNews(String symbol, LocalDate from, LocalDate to);
}
