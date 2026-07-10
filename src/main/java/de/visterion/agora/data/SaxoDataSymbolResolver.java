package de.visterion.agora.data;

import de.visterion.agora.trading.saxo.SaxoDataAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Symbol → Saxo UIC resolution for the data layer, using the Yahoo suffix convention so the
 * same symbol string means the same instrument across the whole provider chain (Yahoo at the
 * end of the chain interprets these suffixes natively).
 *
 * <p>Curated, live-verified suffix map only — an unmapped suffix throws UNAVAILABLE so the
 * chain falls through to a provider that speaks it (the map never needs to be complete).
 * Saxo's ExchangeId for Xetra is FSE, not XETR. Without a suffix the symbol is treated as a
 * US equity and requires an exact ticker match on NYSE/NASDAQ (only reached when Alpaca is
 * down). Successful lookups are cached for 24h, keyed by the full symbol string. Failed
 * lookups are negatively cached for 60s so a burst of requests for an unresolvable/rate-limited
 * symbol doesn't hammer the Saxo instrument-search endpoint.
 */
@Component
public class SaxoDataSymbolResolver {

    private static final Map<String, String> SUFFIX_TO_EXCHANGE = Map.of(
            "DE", "FSE",    // Deutsche Börse (XETRA)
            "MI", "MIL",    // Borsa Italiana
            "TO", "TSE");   // Toronto
    private static final Set<String> US_EXCHANGES = Set.of("NYSE", "NASDAQ");
    private static final long TTL_MILLIS = 24 * 3600 * 1000L;
    private static final long NEGATIVE_TTL_MILLIS = 60 * 1000L;

    private final SaxoDataAccess access;
    private final TtlCache<String, Long> cache;
    private final TtlCache<String, MarketDataException> failureCache;

    @Autowired
    public SaxoDataSymbolResolver(SaxoDataAccess access) {
        this(access, System::currentTimeMillis);
    }

    SaxoDataSymbolResolver(SaxoDataAccess access, LongSupplier nowMillis) {
        this.access = access;
        this.cache = new TtlCache<>(TTL_MILLIS, 4096, nowMillis);
        this.failureCache = new TtlCache<>(NEGATIVE_TTL_MILLIS, 4096, nowMillis);
    }

    /** Resolves to a UIC or throws MarketDataException (UNAVAILABLE / NOT_FOUND). A prior
     *  failure for the same symbol within the last 60s is replayed from the negative cache
     *  instead of re-hitting Saxo. */
    public long resolve(String symbol) {
        Optional<MarketDataException> cachedFailure = failureCache.peek(symbol);
        if (cachedFailure.isPresent()) {
            throw cachedFailure.get();
        }
        try {
            return cache.get(symbol, () -> lookup(symbol));
        } catch (MarketDataException e) {
            failureCache.put(symbol, e);
            throw e;
        }
    }

    private long lookup(String symbol) {
        String bearer = access.bearer().orElseThrow(() -> new MarketDataException(
                MarketDataException.Kind.UNAVAILABLE, "saxo: no active session", null));

        String ticker = symbol;
        String exchangeId = null;
        int dot = symbol.lastIndexOf('.');
        if (dot > 0) {
            String suffix = symbol.substring(dot + 1).toUpperCase(Locale.ROOT);
            exchangeId = SUFFIX_TO_EXCHANGE.get(suffix);
            if (exchangeId == null) {
                throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                        "saxo: unmapped exchange suffix ." + suffix + " for " + symbol, null);
            }
            ticker = symbol.substring(0, dot);
        }

        JsonNode root;
        try {
            String keywords = ticker;
            String exchange = exchangeId;
            root = access.http().get()
                    .uri(uri -> {
                        var b = uri.path("/ref/v1/instruments")
                                .queryParam("Keywords", keywords)
                                .queryParam("AssetTypes", "Stock")
                                .queryParam("$top", 10);
                        if (exchange != null) b = b.queryParam("ExchangeId", exchange);
                        return b.build();
                    })
                    .header("Authorization", bearer)
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo: instrument search failed for " + symbol + ": " + e.getMessage(), e);
        }

        JsonNode data = root == null ? null : root.path("Data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "saxo: no instrument found for " + symbol, null);
        }

        Optional<Long> uic = exchangeId != null
                ? pickForExchange(data, exchangeId, ticker)
                : pickUsExact(data, ticker);
        return uic.orElseThrow(() -> new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                "saxo: no matching instrument for " + symbol, null));
    }

    /** Suffix path: prefer exact ticker match within the exchange, else first hit on it. */
    private Optional<Long> pickForExchange(JsonNode data, String exchangeId, String ticker) {
        Long first = null;
        for (JsonNode hit : data) {
            if (!exchangeId.equals(hit.path("ExchangeId").asString(""))) continue;
            long id = hit.path("Identifier").asLong(0);
            if (id == 0) continue;
            // NOTE: for German symbols the Yahoo query ticker (SAP) intentionally differs from
            // the Saxo symbol (SAPG:xetr), so this exact-match branch rarely fires — first-hit-
            // in-exchange below is the expected resolution mechanism. Do not "fix" this.
            if (tickerOf(hit).equalsIgnoreCase(ticker)) return Optional.of(id);
            if (first == null) first = id;
        }
        return Optional.ofNullable(first);
    }

    /** No-suffix path: exact ticker match on a US exchange, or nothing. */
    private Optional<Long> pickUsExact(JsonNode data, String ticker) {
        for (JsonNode hit : data) {
            if (!US_EXCHANGES.contains(hit.path("ExchangeId").asString(""))) continue;
            long id = hit.path("Identifier").asLong(0);
            if (id != 0 && tickerOf(hit).equalsIgnoreCase(ticker)) return Optional.of(id);
        }
        return Optional.empty();
    }

    /** "SAPG:xetr" → "SAPG". */
    private static String tickerOf(JsonNode hit) {
        String s = hit.path("Symbol").asString("");
        int colon = s.indexOf(':');
        return colon > 0 ? s.substring(0, colon) : s;
    }
}
