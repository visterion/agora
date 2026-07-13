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
        long uic = resolver.resolve(symbol);
        return quoteByUic(uic, symbol);
    }

    @Override
    public Quote quote(Instrument inst) {
        if (inst.uic() == null) throw new MarketDataException(
                MarketDataException.Kind.UNAVAILABLE, "saxo: no uic for " + inst.rawInput(), null);
        return quoteByUic(inst.uic(), inst.rawInput());
    }

    private Quote quoteByUic(long uic, String label) {
        String bearer = requireBearer();

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
                    "saxo infoprices failed for " + label + ": " + e.getMessage(), e);
        }
        if (root == null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo returned empty infoprice for " + label, null);
        }

        JsonNode q = root.path("Quote");
        if ("NoAccess".equals(q.path("PriceTypeBid").asString(""))
                || "NoAccess".equals(q.path("PriceTypeAsk").asString(""))) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo market data not enabled (NoAccess) for " + label, null);
        }
        BigDecimal price = bd(q.path("Mid"));
        if (price.signum() == 0) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo infoprice has no Mid for " + label, null);
        }
        String currency = root.path("DisplayAndFormat").path("Currency").asString("");
        if (currency.isBlank()) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo infoprice has no currency for " + label, null);
        }
        BigDecimal pct = bd(root.path("PriceInfo").path("PercentChange"));
        MinorUnitCurrency n = MinorUnitCurrency.of(currency);
        return new Quote(label, n.apply(price), pct, n.currency());
    }

    @Override
    public List<OhlcBar> ohlc(String symbol, int days) {
        long uic = resolver.resolve(symbol);
        return ohlcByUic(uic, days, symbol);
    }

    @Override
    public List<OhlcBar> ohlc(Instrument inst, int days) {
        if (inst.uic() == null) throw new MarketDataException(
                MarketDataException.Kind.UNAVAILABLE, "saxo: no uic for " + inst.rawInput(), null);
        return ohlcByUic(inst.uic(), days, inst.rawInput());
    }

    private List<OhlcBar> ohlcByUic(long uic, int days, String label) {
        String bearer = requireBearer();
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
                    "saxo charts failed for " + label + ": " + e.getMessage(), e);
        }
        if (root == null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "saxo returned empty chart for " + label, null);
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
        // An empty chart (e.g. brand-new/illiquid instruments) means "not served here" — throw
        // NOT_FOUND rather than caching an empty success, so the chain can still fall through.
        if (out.isEmpty()) {
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + label + " has no bars at Saxo", null);
        }
        // M-D7: Saxo's chart endpoint caps Count at 1200. If more bars were requested than
        // that cap and Saxo returned a full cap's worth, we cannot tell whether that's
        // "exactly 1200 bars of history exist" or "there's more, but Saxo truncated it" —
        // treat it as NOT_FOUND (not a silently-truncated success) so MarketDataService's
        // fallback chain gets a chance to serve the fuller history from another provider.
        // A genuinely short history (out.size() < count) is real data, not truncation, and
        // is returned normally.
        if (days > count && out.size() >= count) {
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "Saxo ohlc capped at " + count + " bars for " + label + " (" + days
                            + " requested) — falling through to a fuller provider", null);
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
