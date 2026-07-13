package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.MarketDataException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Dedicated Yahoo client: its own HttpClient with a cookie jar (the shared DataHttp has
 *  none), single-flight crumb caching, and the timeseries / search calls. */
@Component
public class YahooCrumbClient {
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String userAgent;
    private final String query1;
    private final String query2;
    private final String fcBaseUrl;
    private final String financeBaseUrl;
    private final AtomicReference<String> crumbRef = new AtomicReference<>();
    private final ReentrantLock lock = new ReentrantLock();

    /** Spring-managed constructor; also usable directly from tests (as the sibling
     *  *ProviderTest classes in this package do) to point every endpoint — including the
     *  cookie-bootstrap hosts, which are real Yahoo apex domains distinct from query1/query2
     *  in production — at a single WireMock base URL. */
    @org.springframework.beans.factory.annotation.Autowired
    public YahooCrumbClient(
            @Value("${agora.data.yahoo.user-agent}") String userAgent,
            @Value("${agora.data.yahoo.base-url:https://query1.finance.yahoo.com}") String query1,
            @Value("${agora.data.yahoo.timeseries-base-url:https://query2.finance.yahoo.com}") String query2,
            @Value("${agora.data.yahoo.fc-base-url:https://fc.yahoo.com}") String fcBaseUrl,
            @Value("${agora.data.yahoo.finance-base-url:https://finance.yahoo.com}") String financeBaseUrl) {
        this.userAgent = userAgent;
        this.query1 = query1;
        this.query2 = query2;
        this.fcBaseUrl = fcBaseUrl;
        this.financeBaseUrl = financeBaseUrl;
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /** Convenience 3-arg constructor for callers/tests that don't need to override the
     *  cookie-bootstrap hosts (they default to the real fc.yahoo.com / finance.yahoo.com). */
    public YahooCrumbClient(String userAgent, String query1, String query2) {
        this(userAgent, query1, query2, "https://fc.yahoo.com", "https://finance.yahoo.com");
    }

    public String crumb() {
        String c = crumbRef.get();
        if (c != null) return c;
        lock.lock();
        try {
            c = crumbRef.get();
            if (c != null) return c;
            return handshake();
        } finally { lock.unlock(); }
    }

    private String handshake() {
        softGet(fcBaseUrl);                                // sets A1 cookie (best-effort, ignore any failure)
        softGet(financeBaseUrl + "/quote/AAPL");           // sets further cookies (best-effort)
        String fresh = get(query1 + "/v1/test/getcrumb").trim();
        if (fresh.isEmpty() || fresh.length() > 20
                || fresh.toLowerCase().contains("too many") || fresh.contains("edge") || fresh.contains("<")) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "yahoo crumb unavailable", null);
        }
        crumbRef.set(fresh);
        return fresh;
    }

    public void invalidateCrumb() { crumbRef.set(null); }

    public JsonNode timeseries(String symbol, String typesCsv) {
        String crumb = crumb();
        String body;
        try {
            body = get(timeseriesUrl(symbol, typesCsv, crumb));
        } catch (MarketDataException e) {
            // one re-handshake on 401/invalid-crumb, then give up
            lock.lock();
            try { invalidateCrumb(); } finally { lock.unlock(); }
            String freshCrumb = crumb();
            body = get(timeseriesUrl(symbol, typesCsv, freshCrumb));
        }
        return mapper.readTree(body);
    }

    private String timeseriesUrl(String symbol, String typesCsv, String crumb) {
        return query2 + "/ws/fundamentals-timeseries/v1/finance/timeseries/" + enc(symbol)
                + "?symbol=" + enc(symbol) + "&type=" + enc(typesCsv)
                + "&merge=false&padTimeSeries=true&period1=1104537600&period2=1799999999&crumb=" + enc(crumb);
    }

    public Optional<String> searchIsin(String isin) {
        String body = get(query2 + "/v1/finance/search?q=" + enc(isin) + "&crumb=" + enc(crumb()));
        JsonNode quotes = mapper.readTree(body).path("quotes");
        if (quotes.isArray() && !quotes.isEmpty()) {
            String sym = quotes.get(0).path("symbol").asString("");
            if (!sym.isBlank()) return Optional.of(sym);
        }
        return Optional.empty();
    }

    private String get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "yahoo " + resp.statusCode(), null);
            }
            return resp.body() == null ? "" : resp.body();
        } catch (MarketDataException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "yahoo unreachable: " + e.getMessage(), e);
        }
    }

    /** Best-effort cookie-bootstrap GET: any failure (non-2xx or unreachable) is ignored —
     *  the crumb handshake proceeds regardless, matching real Yahoo behavior where these
     *  bootstrap calls commonly return non-2xx yet still set the cookie. */
    private void softGet(String url) {
        try { get(url); } catch (Exception ignored) { /* best-effort */ }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
