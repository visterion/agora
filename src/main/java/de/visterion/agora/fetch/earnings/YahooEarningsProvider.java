package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.DataHttp;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Yahoo earnings-calendar fallback provider. The endpoint is market-wide, so results are filtered by
 * symbol client-side. Any fetch failure throws MarketDataException(UNAVAILABLE); an empty rows array
 * yields an empty list (not an error). Ports Dracul's YahooEarningsAdapter to Agora's neutral DTOs.
 */
@Component
@Order(10)
public class YahooEarningsProvider implements EarningsProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooEarningsProvider.class);

    /** Yahoo's calendar listings are US-centric; the event date is the exchange-local (Eastern) calendar day. */
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("America/New_York");
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;

    private final RestClient client;
    private final TtlCache<String, List<EarningsEvent>> windowCache;
    private final Set<String> warming = ConcurrentHashMap.newKeySet();

    @Autowired
    public YahooEarningsProvider(
            @Value("${agora.data.yahoo.base-url}") String baseUrl,
            @Value("${agora.data.yahoo.user-agent}") String userAgent,
            @Value("${agora.fetch.earnings.attempt-timeout-ms:4000}") long timeoutMs,
            @Value("${agora.data.cache.ttl.fundamentals-seconds:21600}") long ttlSeconds) {
        this(baseUrl, userAgent, timeoutMs, ttlSeconds, System::currentTimeMillis);
    }

    /** Test/internal constructor with an injectable time source for the page cache. */
    YahooEarningsProvider(String baseUrl, String userAgent, long timeoutMs, long ttlSeconds, LongSupplier now) {
        this.client = DataHttp.clientBuilder(timeoutMs)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.windowCache = new TtlCache<>(ttlSeconds * 1000L, 64, now);
    }

    /** Convenience constructor (default per-request timeout and window-cache TTL); used by tests. */
    public YahooEarningsProvider(String baseUrl, String userAgent) {
        this(baseUrl, userAgent, 15_000L, 21_600L, System::currentTimeMillis);
    }

    @Override
    public String name() { return "yahoo"; }

    /**
     * Reads the cached market-wide window; on a miss it starts an asynchronous warm and returns
     * empty so the caller is not billed for the crawl.
     *
     * <p>The crawl pages a market-wide calendar (up to {@code MAX_PAGES} requests) and cannot
     * finish inside the merge budget. Running it inline would mean it is cancelled every time,
     * the cache never fills, and every uncovered ticker pays the full budget forever — with a
     * perfectly healthy Yahoo. So the request path only ever reads.
     *
     * <p>Equivalent to {@link #window(LocalDate, LocalDate, Runnable, Runnable)} with no-op
     * outcome callbacks; kept for callers (and tests) that don't need to react to a warm's
     * success/failure.
     */
    public Optional<List<EarningsEvent>> window(LocalDate from, LocalDate to) {
        return window(from, to, () -> {}, () -> {});
    }

    /**
     * Same read-then-warm-async behaviour as {@link #window(LocalDate, LocalDate)}, but reports
     * the outcome of a freshly <em>started</em> warm via {@code onWarmSuccess}/{@code
     * onWarmFailure} (a cache hit or an already-in-flight warm for the same key invokes neither).
     *
     * <p>This provider has no cooldown of its own — {@link EarningsService} owns the shared
     * {@link ProviderCooldown} and is the one deciding whether to call this method at all. These
     * callbacks are how a warm's outcome, which only becomes known asynchronously after this
     * method has already returned, gets fed back into that cooldown: a chronically failing Yahoo
     * calendar must stop being re-crawled (up to {@code MAX_PAGES} requests) on every single
     * cache-miss request instead of retrying forever.
     */
    public Optional<List<EarningsEvent>> window(LocalDate from, LocalDate to,
                                                 Runnable onWarmSuccess, Runnable onWarmFailure) {
        String key = "yahooearn:" + from + ":" + to;
        Optional<List<EarningsEvent>> hit = windowCache.peek(key);
        if (hit.isPresent()) return hit;
        if (warming.add(key)) {
            Thread.ofVirtual().name("yahoo-earnings-warm").start(() -> {
                try {
                    windowCache.put(key, earnings(null, from, to));
                    onWarmSuccess.run();
                } catch (RuntimeException e) {
                    log.debug("yahoo earnings warm failed for {}..{}", from, to, e);
                    onWarmFailure.run();
                } finally {
                    warming.remove(key);
                }
            });
        }
        return Optional.empty();
    }

    @Override
    public List<EarningsEvent> earnings(String symbol, LocalDate from, LocalDate to) {
        boolean marketWide = symbol == null || symbol.isBlank();
        String want = marketWide ? "" : symbol.toUpperCase();
        List<EarningsEvent> out = new ArrayList<>();

        // Busy calendar days can exceed one page; paginate by offset until the requested symbol
        // is found, a short (last) page is returned, or the page cap is hit.
        for (int page = 0; page < MAX_PAGES; page++) {
            int offset = page * PAGE_SIZE;
            JsonNode body = fetchPage(from, to, offset);

            int rowCount = 0;
            boolean found = false;
            for (JsonNode row : body.path("rows")) {
                rowCount++;
                String ticker = row.path("ticker").asString("").toUpperCase();
                if (ticker.isEmpty()) continue;
                if (!marketWide && !ticker.equals(want)) continue;
                LocalDate date = eventDate(row);
                if (date == null) continue;
                out.add(new EarningsEvent(ticker, date,
                        dec(row, "epsestimate"), dec(row, "epsactual"), dec(row, "epssurprisepct"),
                        null, null));
                if (!marketWide) found = true;
            }
            if (found) break;
            if (rowCount < PAGE_SIZE) break;
        }
        return out;
    }

    private JsonNode fetchPage(LocalDate from, LocalDate to, int offset) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/v1/finance/calendar/earnings")
                            .queryParam("startdt", from.toString())
                            .queryParam("enddt", to.toString())
                            .queryParam("offset", offset)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "yahoo earnings HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "yahoo earnings unreachable: " + e.getMessage(), e);
        }
        if (body == null)
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "empty earnings body", null);
        return body;
    }

    /** Event date in the exchange-local (Eastern) calendar day, not the raw UTC calendar day. */
    private static LocalDate eventDate(JsonNode row) {
        String dt = row.path("startdatetime").asString("");
        if (dt.isEmpty()) return null;
        try {
            return Instant.parse(dt).atZone(EXCHANGE_ZONE).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal dec(JsonNode row, String field) {
        JsonNode n = row.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asString("");
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }
}
