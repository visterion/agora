package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.DataHttp;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.ProviderErrors;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Nasdaq earnings-calendar provider. Key-less, day-granular: one request per calendar day,
 * cached per day so a wide window is paid for once and then shared across every symbol and
 * every overlapping window.
 *
 * <p>Supplies {@code epsForecast} only — there is no {@code epsActual} here, which is why
 * {@link #coverage()} is {@link EarningsCoverage#FUTURE_ONLY}: for past days this source has
 * nothing to add and must not be mistaken for a source that answered.
 */
@Component
@Order(5)
public class NasdaqEarningsProvider implements EarningsProvider {

    /** Nasdaq's calendar days are US-Eastern; a UTC "today" would drop post-market events. */
    static final ZoneId EXCHANGE_ZONE = ZoneId.of("America/New_York");

    private final RestClient client;
    private final int dayCap;
    private final TtlCache<String, List<EarningsEvent>> dayCache;
    private final Supplier<LocalDate> todayEt;
    private volatile boolean lastCallTruncated;

    @Autowired
    public NasdaqEarningsProvider(
            @Value("${agora.data.nasdaq.base-url}") String baseUrl,
            @Value("${agora.data.nasdaq.user-agent}") String userAgent,
            @Value("${agora.fetch.earnings.attempt-timeout-ms:4000}") long timeoutMs,
            @Value("${agora.data.nasdaq.day-cap:95}") int dayCap,
            @Value("${agora.data.cache.ttl.fundamentals-seconds:21600}") long ttlSeconds) {
        this(baseUrl, userAgent, timeoutMs, dayCap, ttlSeconds,
                System::currentTimeMillis, () -> LocalDate.now(EXCHANGE_ZONE));
    }

    NasdaqEarningsProvider(String baseUrl, String userAgent, long timeoutMs, int dayCap,
                           long ttlSeconds, LongSupplier now, Supplier<LocalDate> todayEt) {
        this.client = DataHttp.clientBuilder(timeoutMs)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.dayCap = dayCap;
        this.dayCache = new TtlCache<>(ttlSeconds * 1000L, 4096, now);
        this.todayEt = todayEt;
    }

    @Override public String name() { return "nasdaq"; }

    @Override public EarningsCoverage coverage() { return EarningsCoverage.FUTURE_ONLY; }

    /** True when the most recent call hit the day cap and therefore returned a partial view. */
    public boolean lastCallTruncated() { return lastCallTruncated; }

    @Override
    public List<EarningsEvent> earnings(String symbol, LocalDate from, LocalDate to) {
        LocalDate today = todayEt.get();
        LocalDate start = from.isBefore(today) ? today : from;   // future days only
        boolean marketWide = symbol == null || symbol.isBlank();
        String want = marketWide ? null : symbol.toUpperCase();

        List<EarningsEvent> out = new ArrayList<>();
        int fetched = 0;
        boolean truncated = false;

        // Nearest-to-today first, so a capped window keeps the most decision-relevant days.
        for (LocalDate d = start; !d.isAfter(to); d = d.plusDays(1)) {
            String key = "nasdaq:" + d;
            List<EarningsEvent> day = dayCache.peek(key).orElse(null);
            if (day == null) {
                if (fetched >= dayCap) { truncated = true; break; }
                day = fetchDay(d);
                dayCache.put(key, day);
                fetched++;
            }
            for (EarningsEvent e : day)
                if (marketWide || e.symbol().equals(want)) out.add(e);
        }
        this.lastCallTruncated = truncated;
        return out;
    }

    private List<EarningsEvent> fetchDay(LocalDate day) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/api/calendar/earnings")
                            .queryParam("date", day.toString())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "nasdaq earnings HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    ProviderErrors.categorize("nasdaq earnings", e), e);
        }
        if (body == null)
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "nasdaq empty earnings body", null);

        List<EarningsEvent> out = new ArrayList<>();
        for (JsonNode row : body.path("data").path("rows")) {
            String sym = row.path("symbol").asString("").trim().toUpperCase();
            if (sym.isEmpty()) continue;
            out.add(new EarningsEvent(sym, day, money(row.path("epsForecast").asString("")),
                    null, null, null, null));
        }
        return out;
    }

    /** Parses Nasdaq's money strings: {@code $1.25}, {@code ($0.40)} (negative), {@code ""}. */
    static BigDecimal money(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.equals("N/A")) return null;
        boolean negative = s.startsWith("(") && s.endsWith(")");
        if (negative) s = s.substring(1, s.length() - 1);
        s = s.replace("$", "").replace(",", "").trim();
        if (s.isEmpty()) return null;
        try {
            BigDecimal v = new BigDecimal(s);
            return negative ? v.negate() : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
