package de.visterion.agora.fetch.edgar;

import de.visterion.agora.data.DataHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Resolves ticker -> zero-padded 10-digit CIK from SEC company_tickers.json.
 *
 * <p>The ticker->CIK map is cached with a 24h TTL (M-F7): SEC lists newly-listed tickers
 * (spinoffs — the core Agora use case) within that window, so a JVM-lifetime cache would
 * make them permanently unresolvable until restart. On a refresh failure the previous
 * (stale) map keeps serving indefinitely; if there was never any real data at all, an empty
 * result is cached briefly (60s, M-F8) so a cold-start outage doesn't force every waiting
 * request thread through a fresh ~15s HTTP call.
 *
 * <p>The HTTP fetch itself runs OUTSIDE any lock — only the map swap is {@code synchronized}
 * (M-F8) — so concurrent readers are never serialized behind a single slow fetch. Concurrent
 * cold-start callers may each independently trigger a fetch; that duplication is acceptable
 * (it is bounded and self-limiting once the cache is warm).
 *
 * <p>Lookups normalize the SEC share-class separator (M-F6): SEC's file uses '-' for share
 * classes (e.g. "BRK-B") while callers commonly write '.' (e.g. "BRK.B") — an exact match is
 * tried first, then the '.'&lt;-&gt;'-' swapped variant. Duplicate tickers in the SEC file
 * keep the FIRST occurrence: SEC orders the file by market cap descending, so the first
 * entry for a duplicated ticker is the larger, more likely intended issuer.
 */
@Component
public class EdgarCikResolver {

    private static final long TTL_MILLIS = Duration.ofHours(24).toMillis();
    private static final long NEGATIVE_TTL_MILLIS = Duration.ofSeconds(60).toMillis();

    private record Snapshot(Map<String, String> tickers, long expiresAtMillis, boolean negative) {}

    private final RestClient http;
    private final LongSupplier nowMillis;

    private volatile Snapshot snapshot;

    @Autowired
    public EdgarCikResolver(@Value("${agora.data.edgar.user-agent}") String userAgent,
                            @Value("${agora.fetch.timeout-ms:15000}") long timeoutMs) {
        this(buildHttpClient(userAgent, timeoutMs), System::currentTimeMillis);
    }

    private static RestClient buildHttpClient(String userAgent, long timeoutMs) {
        JdkClientHttpRequestFactory rf = DataHttp.requestFactory(timeoutMs);
        return RestClient.builder()
                .requestFactory(rf)
                .baseUrl("https://www.sec.gov")
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    // Test constructor: RestClient with base url already set, real clock.
    EdgarCikResolver(RestClient http) {
        this(http, System::currentTimeMillis);
    }

    // Test constructor: injectable clock for TTL/expiry tests.
    EdgarCikResolver(RestClient http, LongSupplier nowMillis) {
        this.http = http;
        this.nowMillis = nowMillis;
    }

    public Optional<String> cik(String ticker) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        String upper = ticker.toUpperCase();
        Map<String, String> tickers = map();
        String cik = tickers.get(upper);
        if (cik == null) {
            cik = tickers.get(swapShareClassSeparator(upper));
        }
        return Optional.ofNullable(cik);
    }

    private static String swapShareClassSeparator(String ticker) {
        StringBuilder swapped = new StringBuilder(ticker.length());
        for (int i = 0; i < ticker.length(); i++) {
            char c = ticker.charAt(i);
            if (c == '.') swapped.append('-');
            else if (c == '-') swapped.append('.');
            else swapped.append(c);
        }
        return swapped.toString();
    }

    private Map<String, String> map() {
        Snapshot local = snapshot;
        long now = nowMillis.getAsLong();
        if (local != null && now < local.expiresAtMillis()) {
            return local.tickers();
        }

        // Fetch OUTSIDE the lock (M-F8) — a ~15s HTTP call must never serialize readers.
        Map<String, String> fetched;
        try {
            fetched = fetchTickers();
        } catch (Exception e) {
            fetched = null;
        }

        synchronized (this) {
            if (fetched != null) {
                if (fetched.isEmpty()) {
                    // Successful-but-empty response: don't poison the cache with an empty
                    // result and don't touch a good stale snapshot — just don't record this
                    // attempt so the next call retries.
                    return snapshot != null ? snapshot.tickers() : fetched;
                }
                Snapshot fresh = new Snapshot(fetched, now + TTL_MILLIS, false);
                snapshot = fresh;
                return fetched;
            }
            // Fetch failed. Real stale data (even if expired) keeps serving indefinitely.
            if (snapshot != null && !snapshot.negative()) {
                return snapshot.tickers();
            }
            // No real data was ever obtained: cache a negative result briefly so a
            // cold-start outage doesn't force every waiting request through another
            // full HTTP timeout.
            Map<String, String> empty = Map.of();
            snapshot = new Snapshot(empty, now + NEGATIVE_TTL_MILLIS, true);
            return empty;
        }
    }

    private Map<String, String> fetchTickers() {
        Map<String, String> built = new HashMap<>();
        JsonNode root = http.get().uri("/files/company_tickers.json")
                .retrieve().body(JsonNode.class);
        if (root != null) {
            for (JsonNode entry : root) {
                String ticker = entry.path("ticker").asString("").toUpperCase();
                long cikValue = entry.path("cik_str").asLong(0);
                if (!ticker.isEmpty() && cikValue > 0) {
                    // First occurrence wins (M-F6): SEC orders the file by market cap
                    // descending, so the first entry for a duplicated ticker is the
                    // larger issuer.
                    built.putIfAbsent(ticker, String.format("%010d", cikValue));
                }
            }
        }
        return built;
    }
}
