package de.visterion.agora.data;

import de.visterion.agora.trading.saxo.SaxoDataAccess;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
 *
 * <p>Cold-cache worst case: a first-ever {@code ohlc} for a symbol makes up to three
 * sequential Saxo calls (instrument search, accounts/me, chart), each bounded by the 4s
 * read timeout — up to ~12s if the gateway stalls all three. Steady state is one call:
 * the UIC is cached 24h and the AccountKey forever; a dead session fails instantly.
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
        String bearer = requireBearer();
        long uic = resolver.resolve(symbol);
        String accountKey = access.accountKey().orElseThrow(() -> new MarketDataException(
                MarketDataException.Kind.UNAVAILABLE, "saxo: no account key available", null));
        int count = Math.min(days, 1200);   // Saxo chart max sample count

        JsonNode root;
        try {
            root = access.http().get()
                    .uri(uri -> uri.path("/chart/v3/charts")
                            .queryParam("Uic", uic)
                            .queryParam("AssetType", "Stock")
                            .queryParam("Horizon", 1440)
                            .queryParam("Count", count)
                            .queryParam("AccountKey", accountKey)
                            .build())
                    .header("Authorization", bearer)
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo charts failed for " + symbol + ": " + e.getMessage(), e);
        }
        if (root == null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo returned empty chart for " + symbol, null);
        }

        List<OhlcBar> out = new ArrayList<>();
        JsonNode data = root.path("Data");
        if (data.isArray()) {
            for (JsonNode b : data) {                        // Saxo returns oldest-first
                String t = b.path("Time").asString("");
                if (t.length() < 10) continue;
                LocalDate date;
                try { date = LocalDate.parse(t.substring(0, 10)); }
                catch (Exception e) { continue; }
                out.add(new OhlcBar(date, bd(b.path("Open")), bd(b.path("High")),
                        bd(b.path("Low")), bd(b.path("Close")),
                        (long) b.path("Volume").asDouble(0)));
            }
        }
        if (out.size() > days) {
            out = new ArrayList<>(out.subList(out.size() - days, out.size()));
        }
        return out;
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
