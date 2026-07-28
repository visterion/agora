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
    private final LongSupplier now;
    private final long attemptWorstCaseMs;

    @Autowired
    public NasdaqEarningsProvider(
            @Value("${agora.data.nasdaq.base-url}") String baseUrl,
            @Value("${agora.data.nasdaq.user-agent}") String userAgent,
            EarningsBudgetPolicy budget,
            @Value("${agora.data.nasdaq.day-cap:95}") int dayCap,
            @Value("${agora.data.cache.ttl.fundamentals-seconds:21600}") long ttlSeconds) {
        this(baseUrl, userAgent, budget.attemptTimeoutMs(), dayCap, ttlSeconds,
                System::currentTimeMillis, () -> LocalDate.now(EXCHANGE_ZONE),
                budget.unthrottledAttemptWorstCaseMs());
    }

    NasdaqEarningsProvider(String baseUrl, String userAgent, long timeoutMs, int dayCap,
                           long ttlSeconds, LongSupplier now, Supplier<LocalDate> todayEt) {
        this(baseUrl, userAgent, timeoutMs, dayCap, ttlSeconds, now, todayEt,
                DataHttp.CONNECT_TIMEOUT_MS + timeoutMs);
    }

    NasdaqEarningsProvider(String baseUrl, String userAgent, long timeoutMs, int dayCap,
                           long ttlSeconds, LongSupplier now, Supplier<LocalDate> todayEt,
                           long attemptWorstCaseMs) {
        this.client = DataHttp.clientBuilder(timeoutMs)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.dayCap = dayCap;
        this.dayCache = new TtlCache<>(ttlSeconds * 1000L, 4096, now);
        this.todayEt = todayEt;
        this.now = now;
        this.attemptWorstCaseMs = attemptWorstCaseMs;
    }

    @Override public String name() { return "nasdaq"; }

    @Override public EarningsCoverage coverage() { return EarningsCoverage.FUTURE_ONLY; }

    /**
     * Unbudgeted entry point (kept for callers outside the merge): the day cap still applies, but
     * nothing stops the loop on elapsed time. Truncation is reported through
     * {@link #earnings(String, LocalDate, LocalDate, long)}; this overload discards it, which is
     * why {@link EarningsService} never calls it.
     */
    @Override
    public List<EarningsEvent> earnings(String symbol, LocalDate from, LocalDate to) {
        return earnings(symbol, from, to, Long.MAX_VALUE).events();
    }

    /**
     * Day-by-day fetch bounded by both the day cap and {@code budgetMs}.
     *
     * <p>The time bound is not decoration. A cold cache over the default {@code
     * get_earnings_calendar} window is 91 sequential requests and over {@code get_earnings_window}
     * up to 366 — neither finishes inside the merge budget at any realistic latency. Without the
     * bound every early call was budget-<em>cancelled</em>: the days it had already fetched were
     * discarded with the cancelled future, and a cancellation deliberately does not trip the
     * cooldown, so the call burned the whole budget and repeated. Stopping one worst-case attempt
     * short of the budget instead means the call returns real data plus {@code truncated=true},
     * the days reached stay in the shared day cache, and successive calls converge.
     */
    @Override
    public ProviderEarnings earnings(String symbol, LocalDate from, LocalDate to, long budgetMs) {
        long startedAt = now.getAsLong();
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
                // Do not START a request that could still be running when the budget expires:
                // the worst case for one day is a full connect plus a full read timeout.
                if (now.getAsLong() - startedAt + attemptWorstCaseMs > budgetMs) {
                    truncated = true;
                    break;
                }
                day = fetchDay(d);
                dayCache.put(key, day);
                fetched++;
            }
            for (EarningsEvent e : day)
                if (marketWide || e.symbol().equals(want)) out.add(e);
        }
        return new ProviderEarnings(out, truncated);
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
