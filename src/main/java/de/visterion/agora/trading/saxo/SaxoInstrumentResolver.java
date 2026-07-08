package de.visterion.agora.trading.saxo;

import de.visterion.agora.data.TtlCache;
import de.visterion.agora.trading.BrokerException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Resolves a plain ticker (AAPL) to Saxo's (Uic, AssetType). Saxo keyword search is
 * fuzzy, so candidates are filtered to an exact base-symbol match; zero or multiple
 * survivors are order-level failures (rejected), not outages. Successful lookups are
 * cached (default 24h) — instrument identity is effectively static intraday.
 */
public class SaxoInstrumentResolver {

    public record ResolvedInstrument(long uic, String assetType, String saxoSymbol) {}

    public static class SymbolResolutionException extends RuntimeException {
        public SymbolResolutionException(String message) { super(message); }
    }

    private final RestClient client;
    private final Supplier<String> bearer;
    private final String exchangeId;
    private final TtlCache<String, ResolvedInstrument> cache;

    public SaxoInstrumentResolver(RestClient client, Supplier<String> bearer,
                                  String exchangeId, long ttlMillis, LongSupplier nowMillis) {
        this.client = client;
        this.bearer = bearer;
        this.exchangeId = (exchangeId == null || exchangeId.isBlank()) ? null : exchangeId;
        this.cache = new TtlCache<>(ttlMillis, nowMillis);
    }

    public ResolvedInstrument resolve(String symbol) {
        return cache.get(symbol.toUpperCase(java.util.Locale.ROOT), () -> lookup(symbol));
    }

    private ResolvedInstrument lookup(String symbol) {
        JsonNode resp;
        try {
            // See SaxoBrokerProvider.account()/positions() for why dynamic values are
            // bound as build(Object...) template variables rather than concatenated or
            // URLEncoder-escaped: RestClient's DefaultUriBuilderFactory runs in
            // TEMPLATE_AND_VALUES mode, so a manually pre-encoded query string passed via
            // uri(String) gets re-encoded wholesale (double encoding). Template variables
            // are encoded exactly once.
            resp = client.get()
                    .uri(uri -> {
                        var b = uri.path("/ref/v1/instruments")
                                .queryParam("Keywords", "{kw}")
                                .queryParam("AssetTypes", "Stock");
                        if (exchangeId != null) {
                            return b.queryParam("ExchangeId", "{ex}").build(symbol, exchangeId);
                        }
                        return b.build(symbol);
                    })
                    .header("Authorization", bearer.get())
                    .retrieve().body(JsonNode.class);
        } catch (BrokerException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo instrument lookup failed: " + e.getMessage(), e);
        }
        List<JsonNode> matches = new ArrayList<>();
        if (resp != null) {
            for (JsonNode n : resp.path("Data")) {
                String base = SaxoBrokerProvider.baseSymbol(n.path("Symbol").asString(""));
                if (base.equalsIgnoreCase(symbol)) matches.add(n);
            }
        }
        if (matches.isEmpty()) throw new SymbolResolutionException("unknown symbol: " + symbol);
        if (matches.size() > 1) {
            throw new SymbolResolutionException("ambiguous symbol: " + symbol + " — set extra.exchange-id");
        }
        JsonNode m = matches.get(0);
        return new ResolvedInstrument(m.path("Identifier").asLong(0),
                m.path("AssetType").asString("Stock"), m.path("Symbol").asString(""));
    }
}
