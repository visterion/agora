package de.visterion.agora.data;

import de.visterion.agora.fetch.alpaca.AlpacaDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Broker-first market-data provider backed by the Alpaca Market Data API
 * ({@code data.alpaca.markets}), using the free IEX feed. Serves {@code quote} (via the snapshot
 * endpoint) and {@code ohlc} (via daily bars). Highest priority ({@code @Order(5)}) so it runs
 * before the free fallback providers.
 *
 * <p>"falls vorhanden" — when credentials are absent ({@link AlpacaDataClient#configured()} false)
 * or any call fails / times out / returns non-2xx, it throws the standard {@code UNAVAILABLE}
 * {@link MarketDataException} so {@code MarketDataService} silently falls through to the next
 * provider. It never blocks or hard-errors the chain. Reuses {@link AlpacaDataClient} for the
 * base URL + header auth (shared with {@code AlpacaSplitProvider}); the client carries the
 * configurable per-request read timeout.
 */
@Component
@Order(5)
public class AlpacaMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(AlpacaMarketDataProvider.class);

    /** Alpaca's market calendar (bar/session boundaries) is anchored to US Eastern time. */
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    /** Alpaca's maximum bars per response page — the same value the single-symbol path sends. */
    private static final int PAGE_LIMIT = 10_000;

    private final AlpacaDataClient client;
    private final Set<String> nonUsSuffixes;
    private final int batchSymbols;
    private final int batchMaxPages;

    /**
     * Constructor bound by Spring via {@code @Value}. Reuses the same
     * {@code agora.fundamentals.non-us-suffixes} property fundamentals routing reads, so the
     * two never drift.
     *
     * @param batchSymbols  symbols per multi-symbol bars request
     *                      ({@code agora.data.alpaca.batch-symbols}). 100 is the size that was
     *                      measured working against the live API on 2026-08-06 (HTTP 200 in
     *                      1.15 s, 100 symbols in one URL); it keeps the query string well
     *                      inside any proxy's URL length limit while cutting an S&P-500 sweep
     *                      to five requests' worth of chunks.
     * @param batchMaxPages hard bound on the {@code next_page_token} follow loop per chunk
     *                      ({@code agora.data.alpaca.batch-max-pages}). A malformed or looping
     *                      token must not spin forever, but the cap must never truncate a
     *                      legitimate request: one chunk carries at most
     *                      {@code batchSymbols} × {@code MAX_FETCH_DAYS} (100 × 1825 ≈ 182 500)
     *                      bars ≈ 19 pages of 10 000, so 50 leaves ~2.5× headroom. Hitting the
     *                      cap is logged as a warning, never silent.
     */
    @Autowired
    public AlpacaMarketDataProvider(AlpacaDataClient client,
            @Value("${agora.fundamentals.non-us-suffixes:" + NonUsSuffixes.DEFAULT_CSV + "}") String nonUsSuffixesCsv,
            @Value("${agora.data.alpaca.batch-symbols:100}") int batchSymbols,
            @Value("${agora.data.alpaca.batch-max-pages:50}") int batchMaxPages) {
        this.client = client;
        this.nonUsSuffixes = NonUsSuffixes.parse(nonUsSuffixesCsv);
        this.batchSymbols = Math.max(1, batchSymbols);
        this.batchMaxPages = Math.max(1, batchMaxPages);
    }

    /** Test/back-compat constructor: default non-US suffix set. */
    public AlpacaMarketDataProvider(AlpacaDataClient client, String nonUsSuffixesCsv) {
        this(client, nonUsSuffixesCsv, 100, 50);
    }

    /** Test/back-compat constructor: default non-US suffix set. */
    public AlpacaMarketDataProvider(AlpacaDataClient client) {
        this(client, NonUsSuffixes.DEFAULT_CSV);
    }

    /** Test constructor: explicit chunk size / page cap, default non-US suffix set. */
    AlpacaMarketDataProvider(AlpacaDataClient client, int batchSymbols, int batchMaxPages) {
        this(client, NonUsSuffixes.DEFAULT_CSV, batchSymbols, batchMaxPages);
    }

    @Override
    public String name() {
        return "alpaca";
    }

    /** US-only broker feed: skip non-US instruments so the fallback chain reaches Saxo/Yahoo
     *  without a wasted 4xx round-trip. */
    @Override
    public boolean canServe(Instrument inst) {
        return !NonUsSuffixes.isNonUs(inst, nonUsSuffixes);
    }

    @Override
    public Quote quote(String symbol) {
        if (!client.configured()) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "alpaca: no api key", null);
        }
        JsonNode snap;
        try {
            snap = client.http().get()
                    .uri(uri -> uri.path("/v2/stocks/{symbol}/snapshot")
                            .queryParam("feed", "iex")
                            .build(symbol))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Alpaca snapshot returned HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    ProviderErrors.categorize("alpaca", e), e);
        }
        if (snap == null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Alpaca returned empty snapshot for " + symbol, null);
        }

        // price: whichever of latestTrade / dailyBar is fresher wins (the free IEX feed's
        // latestTrade can lag well behind the daily bar close, e.g. after-hours or during
        // low-volume IEX-only trading), falling back to whichever one is present.
        BigDecimal tradePrice = bd(snap.path("latestTrade").path("p"));
        BigDecimal barPrice = bd(snap.path("dailyBar").path("c"));
        Instant tradeTime = instantOf(snap.path("latestTrade").path("t"));
        Instant barTime = instantOf(snap.path("dailyBar").path("t"));
        BigDecimal price;
        if (tradePrice.signum() != 0 && barPrice.signum() != 0) {
            price = (barTime != null && tradeTime != null && barTime.isAfter(tradeTime)) ? barPrice : tradePrice;
        } else if (tradePrice.signum() != 0) {
            price = tradePrice;
        } else {
            price = barPrice;
        }
        if (price.signum() == 0) {
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " not found at Alpaca", null);
        }

        BigDecimal prevClose = bd(snap.path("prevDailyBar").path("c"));
        BigDecimal dayChangePercent = BigDecimal.ZERO;
        if (prevClose.signum() != 0) {
            dayChangePercent = price.subtract(prevClose)
                    .divide(prevClose, new MathContext(6, RoundingMode.HALF_UP))
                    .multiply(new BigDecimal("100"))
                    .setScale(4, RoundingMode.HALF_UP);
        }
        return new Quote(symbol, price, dayChangePercent, "USD");
    }

    @Override
    public List<OhlcBar> ohlc(String symbol, int days) {
        if (!client.configured()) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "alpaca: no api key", null);
        }
        String start = startDate(days);
        JsonNode root;
        try {
            root = client.http().get()
                    .uri(uri -> uri.path("/v2/stocks/{symbol}/bars")
                            .queryParam("timeframe", "1Day")
                            .queryParam("feed", "iex")
                            .queryParam("start", start)
                            .queryParam("limit", PAGE_LIMIT)
                            // Split-adjusted closes so bars stay continuous across a symbol's
                            // split history and are comparable with the Yahoo/TwelveData
                            // fallback providers, which both return adjusted series.
                            .queryParam("adjustment", "split")
                            .build(symbol))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Alpaca bars returned HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    ProviderErrors.categorize("alpaca", e), e);
        }
        if (root == null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Alpaca returned empty bars for " + symbol, null);
        }

        List<OhlcBar> out = new ArrayList<>();
        appendBars(root.path("bars"), out);
        // Alpaca returns HTTP 200 with {"bars":null} for symbols it doesn't know (all non-US
        // symbols on the free IEX feed). Treat that as "not served here" so MarketDataService
        // falls through to the next provider, instead of caching an empty success.
        if (out.isEmpty()) {
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " has no bars at Alpaca", null);
        }
        return trimToLast(out, days);
    }

    @Override
    public boolean supportsOhlcBatch() { return true; }

    /**
     * Multi-symbol daily bars via {@code GET /v2/stocks/bars?symbols=A,B,C} — one request per
     * chunk of {@code batchSymbols} instead of one per symbol. Returns only the symbols Alpaca
     * actually served; a symbol missing from the response map is not an error (that is Alpaca's
     * per-symbol "not served here" signal, the multi-symbol equivalent of the single path's
     * {@code {"bars":null}}), the caller decides what a gap means.
     *
     * <p>The series a symbol gets here is bar-for-bar the series {@link #ohlc(String, int)} would
     * return: same {@code start}, {@code feed=iex}, {@code timeframe=1Day},
     * {@code adjustment=split}, same parsing and the same trim to the last {@code days} bars —
     * the parsing and trimming are literally the same two methods. If the two paths could
     * disagree, a 52-week low would depend on which path happened to fetch it.
     *
     * <p><strong>The page loop is load-bearing.</strong> Measured against the live API on
     * 2026-08-06: {@code limit} is a <em>global</em> bar budget across all requested symbols,
     * not a per-symbol one — with {@code limit=6} and three symbols only the first symbol came
     * back, plus a {@code next_page_token}. A version without this loop returns a response that
     * looks fine and silently omits most of the universe.
     *
     * <p><strong>An unfinished page walk delivers nothing for that chunk.</strong> If page N fails
     * (HTTP error, 429, timeout, empty body) or the page cap is reached, every symbol read from
     * that chunk's earlier pages is discarded and the chunk's symbols are reported as not
     * delivered. That is deliberately harsher than "keep what was read": because {@code limit} is
     * a global bar budget, the page break falls <em>inside</em> one symbol's series, and a half
     * series is indistinguishable downstream from a genuinely short history — it reads as a young
     * listing and is filtered out quietly, instead of as a degradation the operator can see. Only
     * the symbol at the page boundary is actually suspect, but Alpaca's response carries no
     * per-symbol completeness marker, so the boundary symbol cannot be identified from the
     * response shape; dropping the chunk is the only version that is right without an
     * unverifiable assumption about fill order. Chunks that finished are unaffected.
     */
    @Override
    public Map<String, List<OhlcBar>> ohlcBatch(List<String> symbols, int days) {
        if (!client.configured()) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "alpaca: no api key", null);
        }
        List<String> wanted = new ArrayList<>(new LinkedHashSet<>(
                symbols.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList()));
        Map<String, List<OhlcBar>> acc = new LinkedHashMap<>();
        if (wanted.isEmpty()) return acc;

        String start = startDate(days);
        for (int i = 0; i < wanted.size(); i += batchSymbols) {
            List<String> chunk = wanted.subList(i, Math.min(i + batchSymbols, wanted.size()));
            Map<String, List<OhlcBar>> chunkBars = new LinkedHashMap<>();
            if (fetchChunk(chunk, start, chunkBars)) {
                acc.putAll(chunkBars);                  // chunks are disjoint: no key collisions
            } else if (!chunkBars.isEmpty()) {
                log.warn("alpaca batch bars: discarding {} partially read symbol(s) of an unfinished "
                        + "{}-symbol chunk — a page break can cut a symbol's series in half, and a half "
                        + "series reads as a short history downstream; they count as not delivered",
                        chunkBars.size(), chunk.size());
            }
        }

        Map<String, List<OhlcBar>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<OhlcBar>> e : acc.entrySet()) {
            if (!e.getValue().isEmpty()) out.put(e.getKey(), trimToLast(e.getValue(), days));
        }
        return out;
    }

    /**
     * Fetches one chunk, following {@code next_page_token} into {@code acc}. Never throws.
     *
     * @return {@code true} iff the walk reached the end of the chunk's pages. {@code false} means
     *         the result in {@code acc} is an unfinished read and the caller must discard it —
     *         see {@link #ohlcBatch}.
     */
    private boolean fetchChunk(List<String> chunk, String start, Map<String, List<OhlcBar>> acc) {
        String csv = String.join(",", chunk);
        // Alpaca echoes symbols upper-cased regardless of how they were sent; map back to the
        // caller's spelling so the result keys are exactly the strings that were requested.
        Map<String, String> byUpper = new LinkedHashMap<>();
        for (String s : chunk) byUpper.putIfAbsent(s.toUpperCase(Locale.ROOT), s);
        String pageToken = null;
        for (int page = 1; page <= batchMaxPages; page++) {
            String token = pageToken;
            JsonNode root;
            try {
                root = client.http().get()
                        .uri(uri -> {
                            uri.path("/v2/stocks/bars")
                               .queryParam("symbols", csv)
                               .queryParam("timeframe", "1Day")
                               .queryParam("feed", "iex")
                               .queryParam("start", start)
                               .queryParam("limit", PAGE_LIMIT)
                               .queryParam("adjustment", "split");
                            if (token != null) uri.queryParam("page_token", token);
                            return uri.build();
                        })
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientResponseException e) {
                log.warn("alpaca batch bars page {} of {} symbols returned HTTP {} — chunk incomplete",
                        page, chunk.size(), e.getStatusCode());
                return false;
            } catch (Exception e) {
                log.warn("alpaca batch bars page {} of {} symbols failed: {} — chunk incomplete",
                        page, chunk.size(), ProviderErrors.categorize("alpaca", e));
                return false;
            }
            if (root == null) {
                log.warn("alpaca batch bars page {} of {} symbols returned an empty body — chunk incomplete",
                        page, chunk.size());
                return false;
            }

            // Multi-symbol shape: bars is an object {SYM: [bar,...]}, not the single path's array.
            JsonNode bars = root.path("bars");
            if (bars.isObject()) {
                for (Map.Entry<String, JsonNode> e : bars.properties()) {
                    String symbol = byUpper.getOrDefault(e.getKey().toUpperCase(Locale.ROOT), e.getKey());
                    appendBars(e.getValue(), acc.computeIfAbsent(symbol, k -> new ArrayList<>()));
                }
            }
            JsonNode next = root.path("next_page_token");
            if (!next.isString() || next.asString("").isEmpty()) return true;  // JSON null ends the walk
            pageToken = next.asString("");
        }
        // Same situation as a failed page, not a success: the walk did not reach the end.
        log.warn("alpaca batch bars hit the {}-page cap for a {}-symbol chunk — chunk incomplete",
                batchMaxPages, chunk.size());
        return false;
    }

    /** Cover weekends/holidays so we get ~{@code days} trading bars, then trim to the most recent. */
    private static String startDate(int days) {
        return LocalDate.now(MARKET_ZONE).minusDays((long) Math.ceil(days * 1.5) + 5).toString();
    }

    /** Parses one symbol's bar array (Alpaca returns oldest-first) and appends to {@code out}.
     *  Shared by the single- and multi-symbol paths so neither can drift from the other. */
    private static void appendBars(JsonNode barsArray, List<OhlcBar> out) {
        if (!barsArray.isArray()) return;
        for (JsonNode b : barsArray) {
            String t = b.path("t").asString("");
            if (t.length() < 10) continue;
            LocalDate date;
            try { date = LocalDate.parse(t.substring(0, 10)); }
            catch (Exception e) { continue; }
            out.add(new OhlcBar(date, bd(b.path("o")), bd(b.path("h")),
                    bd(b.path("l")), bd(b.path("c")), b.path("v").asLong(0)));
        }
    }

    /** Keep only the most recent {@code days} bars (still oldest-first). */
    private static List<OhlcBar> trimToLast(List<OhlcBar> bars, int days) {
        if (bars.size() <= days) return bars;
        return new ArrayList<>(bars.subList(bars.size() - days, bars.size()));
    }

    private static BigDecimal bd(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(node.asString("0")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    /** Parses Alpaca's RFC-3339 "t" timestamp field, or {@code null} if absent/unparseable. */
    private static Instant instantOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try { return Instant.parse(node.asString("")); }
        catch (Exception e) { return null; }
    }
}
