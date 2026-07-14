package de.visterion.agora.data;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Keyless Yahoo Finance market-data provider.
 * Ports Dracul's YahooMarketDataAdapter to Agora's neutral DTOs.
 */
@Component
@Order(30)
public class YahooMarketDataProvider implements MarketDataProvider {

    // One retry on 429/5xx (per plan spec)
    private static final int MAX_RETRIES = 1;

    private final RestClient client;
    private final long retryBaseMs;

    /** Package-private constructor for tests: base-url + UA + explicit retry delay (pass 0 for no sleep), default timeout. */
    YahooMarketDataProvider(String baseUrl, String userAgent, long retryBaseMs) {
        this(baseUrl, userAgent, retryBaseMs, 4000L);
    }

    /**
     * Full / Spring-wired constructor: base-url + UA + retry delay + per-request read timeout (millis).
     * Applies the configurable read timeout ({@code agora.data.provider-timeout-ms}) so a slow Yahoo
     * call fails fast into the next provider; retry base delay defaults to 500ms.
     */
    @Autowired
    public YahooMarketDataProvider(
            @Value("${agora.data.yahoo.base-url}") String baseUrl,
            @Value("${agora.data.yahoo.user-agent}") String userAgent,
            @Value("${agora.data.yahoo.retry-base-ms:500}") long retryBaseMs,
            @Value("${agora.data.provider-timeout-ms:4000}") long timeoutMs) {
        this.client = DataHttp.clientBuilder(timeoutMs)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.retryBaseMs = retryBaseMs;
    }

    @Override
    public String name() {
        return "yahoo";
    }

    @Override
    public Quote quote(String symbol) {
        JsonNode body;
        try {
            body = getWithRetry(() -> client.get()
                    .uri(uri -> uri.path("/v8/finance/chart/{symbol}")
                            .queryParam("range", "1d")
                            .queryParam("interval", "1d")
                            .build(symbol))
                    .retrieve()
                    .body(JsonNode.class));
        } catch (RestClientResponseException e) {
            throw new MarketDataException(
                    statusKind(e), "Yahoo Finance returned HTTP " + e.getStatusCode(), e);
        } catch (MarketDataException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketDataException(
                    MarketDataException.Kind.UNAVAILABLE,
                    "Yahoo Finance unreachable: " + e.getMessage(), e);
        }

        if (body == null) {
            throw new MarketDataException(
                    MarketDataException.Kind.UNAVAILABLE,
                    "Empty body from Yahoo Finance", null);
        }

        JsonNode result = body.path("chart").path("result");
        if (!result.isArray() || result.isEmpty() || result.get(0).isNull()) {
            throw new MarketDataException(
                    MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " not found", null);
        }

        JsonNode meta = result.get(0).path("meta");
        BigDecimal price = bd(meta.path("regularMarketPrice"));

        // Fall back to last close if regularMarketPrice is zero/missing
        if (price.compareTo(BigDecimal.ZERO) == 0) {
            JsonNode closes = result.get(0).path("indicators")
                    .path("quote").path(0).path("close");
            if (closes.isArray()) {
                for (int i = closes.size() - 1; i >= 0; i--) {
                    JsonNode c = closes.path(i);
                    if (!c.isNull() && !c.isMissingNode()) {
                        price = bd(c);
                        break;
                    }
                }
            }
        }

        BigDecimal prevClose = bd(meta.path("chartPreviousClose"));

        MinorUnitCurrency norm = MinorUnitCurrency.of(meta.path("currency").asString("USD"));
        price = norm.apply(price);
        prevClose = norm.apply(prevClose);

        // M-D2: a zero/unresolvable price is not a valid quote — fall through the provider
        // chain instead of caching a poisoned zero.
        if (price.compareTo(BigDecimal.ZERO) == 0) {
            throw new MarketDataException(
                    MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " has no usable price at Yahoo", null);
        }

        BigDecimal dayChangePercent = BigDecimal.ZERO;
        if (prevClose.compareTo(BigDecimal.ZERO) != 0) {
            dayChangePercent = price.subtract(prevClose)
                    .divide(prevClose, new MathContext(6, RoundingMode.HALF_UP))
                    .multiply(new BigDecimal("100"))
                    .setScale(4, RoundingMode.HALF_UP);
        }

        return new Quote(symbol, price, dayChangePercent, norm.currency());
    }

    @Override
    public List<OhlcBar> ohlc(String symbol, int days) {
        String range = mapDaysToRange(days);
        JsonNode body;
        try {
            body = getWithRetry(() -> client.get()
                    .uri(uri -> uri.path("/v8/finance/chart/{symbol}")
                            .queryParam("range", range)
                            .queryParam("interval", "1d")
                            .build(symbol))
                    .retrieve()
                    .body(JsonNode.class));
        } catch (RestClientResponseException e) {
            throw new MarketDataException(
                    statusKind(e), "Yahoo Finance OHLC returned HTTP " + e.getStatusCode(), e);
        } catch (MarketDataException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketDataException(
                    MarketDataException.Kind.UNAVAILABLE,
                    "Yahoo Finance OHLC unreachable: " + e.getMessage(), e);
        }

        if (body == null) {
            throw new MarketDataException(
                    MarketDataException.Kind.UNAVAILABLE,
                    "Empty body from Yahoo Finance OHLC", null);
        }

        JsonNode result = body.path("chart").path("result");
        if (!result.isArray() || result.isEmpty() || result.get(0).isNull()) {
            throw new MarketDataException(
                    MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " not found in Yahoo OHLC", null);
        }

        JsonNode r0 = result.get(0);
        JsonNode meta = r0.path("meta");
        MinorUnitCurrency norm = MinorUnitCurrency.of(meta.path("currency").asString("USD"));
        ZoneId zone = resolveZone(meta.path("exchangeTimezoneName"));

        JsonNode timestamps = r0.path("timestamp");
        JsonNode quote = r0.path("indicators").path("quote").path(0);
        JsonNode opens   = quote.path("open");
        JsonNode highs   = quote.path("high");
        JsonNode lows    = quote.path("low");
        JsonNode closes  = quote.path("close");
        JsonNode volumes = quote.path("volume");

        List<OhlcBar> out = new ArrayList<>();
        if (timestamps.isArray()) {
            for (int i = 0; i < timestamps.size(); i++) {
                // H2: a bar with ANY missing O/H/L/C is unusable — skip it rather than
                // fabricating a ZERO that would corrupt 52w/ATR/chandelier calculations.
                if (isMissing(opens, i) || isMissing(highs, i) || isMissing(lows, i) || isMissing(closes, i)) {
                    continue;
                }

                LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong())
                        .atZone(zone)
                        .toLocalDate();
                BigDecimal open   = norm.apply(bd(opens.path(i)));
                BigDecimal high   = norm.apply(bd(highs.path(i)));
                BigDecimal low    = norm.apply(bd(lows.path(i)));
                BigDecimal close  = norm.apply(bd(closes.path(i)));
                long volume = volumes.path(i).asLong(0);

                out.add(new OhlcBar(date, open, high, low, close, volume));
            }
        }
        if (out.isEmpty()) {
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " has no OHLC bars at Yahoo", null);
        }
        // M-D5: the mapped range may cover more history than requested — trim to the
        // requested window (keep the most recent `days` bars) so results are provider-
        // consistent regardless of which Yahoo range bucket satisfied the request.
        if (out.size() > days) {
            out = new ArrayList<>(out.subList(out.size() - days, out.size()));
        }
        // Yahoo returns oldest-first — no reverse needed
        return out;
    }

    /**
     * Maps requested day count to a Yahoo Finance range string that comfortably covers
     * it (trading days per bucket run below the raw calendar length, so weekends/holidays
     * never starve the request). Callers must trim the result to the requested {@code days}
     * afterward — see {@link #ohlc(String, int)}.
     */
    static String mapDaysToRange(int days) {
        if (days <= 5)    return "5d";
        if (days <= 30)   return "1mo";
        if (days <= 90)   return "3mo";
        if (days <= 180)  return "6mo";
        if (days <= 250)  return "1y";
        if (days <= 500)  return "2y";
        if (days <= 1200) return "5y";
        return "10y";
    }

    /** Resolves the exchange-local zone for bar-date conversion; falls back to UTC (M-D3). */
    private static ZoneId resolveZone(JsonNode tzNode) {
        if (tzNode == null || tzNode.isNull() || tzNode.isMissingNode()) return ZoneOffset.UTC;
        String tz = tzNode.asString(null);
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    private static boolean isMissing(JsonNode array, int i) {
        JsonNode n = array.path(i);
        return n.isNull() || n.isMissingNode();
    }


    /** 404 from Yahoo means the symbol doesn't exist there — NOT_FOUND, not UNAVAILABLE. */
    private static MarketDataException.Kind statusKind(RestClientResponseException e) {
        return e.getStatusCode().value() == 404
                ? MarketDataException.Kind.NOT_FOUND
                : MarketDataException.Kind.UNAVAILABLE;
    }

    /** Runs the Yahoo GET, retrying on HTTP 429/5xx with exponential backoff. */
    private JsonNode getWithRetry(java.util.function.Supplier<JsonNode> call) {
        RestClientResponseException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status != 429 && (status < 500 || status > 599)) throw e;
                last = e;
                if (attempt == MAX_RETRIES) break;
                if (retryBaseMs > 0) {
                    try {
                        Thread.sleep(retryBaseMs * (1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new MarketDataException(
                MarketDataException.Kind.UNAVAILABLE,
                "Yahoo Finance returned HTTP " + last.getStatusCode() + " after retries", last);
    }

    private static BigDecimal bd(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(node.asString("0")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
