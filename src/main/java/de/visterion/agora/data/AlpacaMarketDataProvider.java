package de.visterion.agora.data;

import de.visterion.agora.fetch.alpaca.AlpacaDataClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    private final AlpacaDataClient client;

    public AlpacaMarketDataProvider(AlpacaDataClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "alpaca";
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
                    "Alpaca snapshot unreachable: " + e.getMessage(), e);
        }
        if (snap == null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Alpaca returned empty snapshot for " + symbol, null);
        }

        // price: latest trade, else the day's close
        BigDecimal price = bd(snap.path("latestTrade").path("p"));
        if (price.signum() == 0) price = bd(snap.path("dailyBar").path("c"));
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
        // Cover weekends/holidays so we get ~`days` trading bars, then trim to the most recent.
        String start = LocalDate.now().minusDays((long) Math.ceil(days * 1.5) + 5).toString();
        JsonNode root;
        try {
            root = client.http().get()
                    .uri(uri -> uri.path("/v2/stocks/{symbol}/bars")
                            .queryParam("timeframe", "1Day")
                            .queryParam("feed", "iex")
                            .queryParam("start", start)
                            .queryParam("limit", 10000)
                            .build(symbol))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Alpaca bars returned HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Alpaca bars unreachable: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Alpaca returned empty bars for " + symbol, null);
        }

        List<OhlcBar> out = new ArrayList<>();
        JsonNode bars = root.path("bars");
        if (bars.isArray()) {
            for (JsonNode b : bars) {                       // Alpaca returns oldest-first
                String t = b.path("t").asString("");
                if (t.length() < 10) continue;
                LocalDate date;
                try { date = LocalDate.parse(t.substring(0, 10)); }
                catch (Exception e) { continue; }
                out.add(new OhlcBar(date, bd(b.path("o")), bd(b.path("h")),
                        bd(b.path("l")), bd(b.path("c")), b.path("v").asLong(0)));
            }
        }
        // Keep only the most recent `days` bars (still oldest-first).
        if (out.size() > days) {
            out = new ArrayList<>(out.subList(out.size() - days, out.size()));
        }
        return out;
    }

    private static BigDecimal bd(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(node.asString("0")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
