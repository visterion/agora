package de.visterion.agora.data;

import de.visterion.agora.trading.saxo.SaxoDataAccess;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * Broker-first market-data provider backed by the Saxo OpenAPI via the SaxoDataAccess bridge
 * (default connection saxo-live). Serves non-US exchanges (Yahoo-suffix symbols like SAP.DE)
 * that Alpaca (@Order(5), US-only IEX) cannot; sits at @Order(7) before the free fallbacks.
 * Prices are 15-min delayed without paid exchange subscriptions — accepted for a fallback.
 *
 * <p>"falls vorhanden": no authorized session, unmapped suffix, NoAccess price type, or any
 * HTTP failure/timeout → UNAVAILABLE so MarketDataService falls through silently. This
 * provider can never block or hard-error the chain.
 */
@Component
@Order(7)
public class SaxoMarketDataProvider implements MarketDataProvider {

    private final SaxoDataAccess access;
    private final SaxoDataSymbolResolver resolver;

    public SaxoMarketDataProvider(SaxoDataAccess access, SaxoDataSymbolResolver resolver) {
        this.access = access;
        this.resolver = resolver;
    }

    @Override
    public String name() {
        return "saxo";
    }

    @Override
    public Quote quote(String symbol) {
        String bearer = requireBearer();
        long uic = resolver.resolve(symbol);

        JsonNode root;
        try {
            root = access.http().get()
                    .uri(uri -> uri.path("/trade/v1/infoprices")
                            .queryParam("Uic", uic)
                            .queryParam("AssetType", "Stock")
                            .queryParam("FieldGroups", "Quote,PriceInfo,DisplayAndFormat")
                            .build())
                    .header("Authorization", bearer)
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo infoprices failed for " + symbol + ": " + e.getMessage(), e);
        }
        if (root == null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo returned empty infoprice for " + symbol, null);
        }

        JsonNode q = root.path("Quote");
        if ("NoAccess".equals(q.path("PriceTypeBid").asString(""))
                || "NoAccess".equals(q.path("PriceTypeAsk").asString(""))) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo market data not enabled (NoAccess) for " + symbol, null);
        }
        BigDecimal price = bd(q.path("Mid"));
        if (price.signum() == 0) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo infoprice has no Mid for " + symbol, null);
        }
        String currency = root.path("DisplayAndFormat").path("Currency").asString("");
        if (currency.isBlank()) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo infoprice has no currency for " + symbol, null);
        }
        BigDecimal pct = bd(root.path("PriceInfo").path("PercentChange"));
        return new Quote(symbol, price, pct, currency);
    }

    @Override
    public List<OhlcBar> ohlc(String symbol, int days) {
        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                "saxo ohlc not implemented yet", null);
    }

    private String requireBearer() {
        return access.bearer().orElseThrow(() -> new MarketDataException(
                MarketDataException.Kind.UNAVAILABLE, "saxo: no active session", null));
    }

    static BigDecimal bd(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(node.asString("0")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
