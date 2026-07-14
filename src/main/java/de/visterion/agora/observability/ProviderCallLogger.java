package de.visterion.agora.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Singleton request interceptor that emits one redacted structured line per outbound provider call. */
public final class ProviderCallLogger implements ClientHttpRequestInterceptor {

    public static final ProviderCallLogger INSTANCE = new ProviderCallLogger();
    private static final Logger log = LoggerFactory.getLogger("agora.providercall");

    private record Config(boolean enabled, int maxBodyChars) {}
    private static final AtomicReference<Config> CONFIG = new AtomicReference<>(new Config(true, 4096));

    private ProviderCallLogger() {}

    public static void configure(boolean enabled, int maxBodyChars) {
        CONFIG.set(new Config(enabled, Math.max(0, maxBodyChars)));
    }

    @Override
    @NonNull
    public ClientHttpResponse intercept(@NonNull org.springframework.http.HttpRequest request,
                                        @NonNull byte[] body,
                                        @NonNull ClientHttpRequestExecution execution) throws IOException {
        Config cfg = CONFIG.get();
        if (!cfg.enabled()) return execution.execute(request, body);

        long start = System.nanoTime();
        ClientHttpResponse response = execution.execute(request, body);
        try {
            byte[] respBytes = readAll(response.getBody());
            ClientHttpResponse buffered = new BufferedClientHttpResponse(response, respBytes);
            try {
                long durMs = (System.nanoTime() - start) / 1_000_000L;
                emit(cfg, request.getMethod().name(), request.getURI(), request.getHeaders(),
                        new String(body, StandardCharsets.UTF_8),
                        response.getStatusCode().value(), respBytes, durMs);
            } catch (Exception ex) {
                log.warn("provider_call logging failed: {}", ex.toString());
            }
            return buffered;
        } catch (Exception ex) {
            log.warn("provider_call logging failed: {}", ex.toString());
            return response;
        }
    }

    /** Bypass entry point for non-RestClient callers (raw JDK HttpClient, inline builders). */
    public static void record(String method, URI uri, Map<String, String> reqHeaders, String reqBody,
                              int status, String respBody, long durMs) {
        Config cfg = CONFIG.get();
        if (!cfg.enabled()) return;
        try {
            HttpHeaders h = new HttpHeaders();
            if (reqHeaders != null) reqHeaders.forEach(h::add);
            byte[] respBytes = respBody == null ? new byte[0] : respBody.getBytes(StandardCharsets.UTF_8);
            emit(cfg, method, uri, h, reqBody, status, respBytes, durMs);
        } catch (Exception ex) {
            log.warn("provider_call logging failed: {}", ex.toString());
        }
    }

    private static void emit(Config cfg, String method, URI uri, HttpHeaders reqHeaders,
                             String reqBody, int status, byte[] respBytes, long durMs) {
        String host = uri.getHost() == null ? "-" : uri.getHost();
        String provider = provider(host);
        String query = ProviderLogRedactor.redactQuery(uri.getRawQuery());
        String symbol = symbol(uri);
        String statusStr = status < 0 ? "ERR" : String.valueOf(status);
        int reqBytes = reqBody == null ? 0 : reqBody.getBytes(StandardCharsets.UTF_8).length;
        String headers = redactedHeaders(reqHeaders);
        String respStr = capped(ProviderLogRedactor.redactBody(new String(respBytes, StandardCharsets.UTF_8)),
                cfg.maxBodyChars());
        String reqStr = reqBody == null || reqBody.isEmpty() ? "-"
                : capped(ProviderLogRedactor.redactBody(reqBody), cfg.maxBodyChars());
        log.info("provider_call provider={} method={} host={} path={} query={} headers={} status={} dur_ms={} "
                        + "symbol={} req_bytes={} resp_bytes={} req_body={} resp_body={}",
                provider, method, host, uri.getPath(), query == null ? "-" : query, headers, statusStr, durMs,
                symbol, reqBytes, respBytes.length, reqStr, respStr);
    }

    private static String redactedHeaders(HttpHeaders reqHeaders) {
        if (reqHeaders == null || reqHeaders.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean[] first = {true};
        reqHeaders.forEach((name, values) -> {
            for (String value : values) {
                if (!first[0]) sb.append("; ");
                first[0] = false;
                sb.append(name).append('=').append(ProviderLogRedactor.redactHeaderValue(name, value));
            }
        });
        sb.append(']');
        return sb.toString();
    }

    private static String capped(String s, int max) {
        if (s == null) return "-";
        if (s.length() <= max) return s;
        int total = s.getBytes(StandardCharsets.UTF_8).length;
        int kept = s.substring(0, max).getBytes(StandardCharsets.UTF_8).length;
        return s.substring(0, max) + "…[+" + Math.max(total - kept, 0) + "b]";
    }

    private static String provider(String host) {
        String h = host.toLowerCase();
        if (h.contains("yahoo")) return "yahoo";
        if (h.contains("finnhub")) return "finnhub";
        if (h.contains("sec.gov")) return "edgar";
        if (h.contains("saxo")) return "saxo";
        if (h.contains("alpaca")) return "alpaca";
        if (h.contains("twelvedata")) return "twelvedata";
        if (h.contains("wikipedia")) return "wikipedia";
        return host;
    }

    private static String symbol(URI uri) {
        String path = uri.getPath();
        if (path != null) {
            int chart = path.indexOf("/chart/");
            if (chart >= 0) {
                String tail = path.substring(chart + "/chart/".length());
                int slash = tail.indexOf('/');
                String sym = slash >= 0 ? tail.substring(0, slash) : tail;
                if (!sym.isBlank()) return sym;
            }
        }
        String q = uri.getQuery();
        if (q != null) {
            for (String p : q.split("&")) {
                if (p.startsWith("symbol=")) return p.substring("symbol=".length());
            }
        }
        return "-";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) { return in.readAllBytes(); }
    }

    /** Re-serves buffered response bytes so the interceptor's read doesn't consume the real consumer's stream. */
    private static final class BufferedClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;
        BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body) { this.delegate = delegate; this.body = body; }
        @Override public org.springframework.http.HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public void close() { delegate.close(); }
        @Override @NonNull public InputStream getBody() { return new ByteArrayInputStream(body); }
        @Override @NonNull public HttpHeaders getHeaders() { return delegate.getHeaders(); }
    }
}
