package de.visterion.agora.security;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolNotFoundException;
import de.visterion.agora.tool.ToolRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Guards /tools/** and /mcp/** with bearer tokens.
 * <ul>
 *   <li>General tools (and /mcp, unknown paths) → accept general ∪ trading ∪ live tokens.</li>
 *   <li>Trading tools (/tools/{name} where namespace()=="trading") → accept trading ∪ live tokens.</li>
 *   <li>/actuator/health → public (no token required).</li>
 * </ul>
 */
@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    /** Request attribute carrying the raw presented bearer token (set only when authorized). */
    public static final String TOKEN_ATTR = "agora.bearerToken";

    /** trading ∪ live — required for trading tools and any unresolvable tool name (fail closed). */
    private final List<byte[]> tradingOrLiveTokens;
    /** general ∪ trading ∪ live — required for everything else. */
    private final List<byte[]> allTokens;
    private final ToolRegistry registry;

    @Autowired
    public BearerTokenFilter(
            @Value("${agora.auth.tokens:}") String general,
            @Value("${agora.trading.tokens:}") String trading,
            @Value("${agora.trading.live-tokens:}") String live,
            ToolRegistry registry) {
        this(parseCsv(general), parseCsv(trading), parseCsv(live), registry);
    }

    BearerTokenFilter(List<String> generalTokens, List<String> tradingTokens,
                      List<String> liveTokens, ToolRegistry registry) {
        this.registry = registry;
        List<byte[]> tradingBytes = toBytes(tradingTokens);
        List<byte[]> liveBytes = toBytes(liveTokens);
        this.tradingOrLiveTokens = concat(tradingBytes, liveBytes);
        this.allTokens = concat(concat(toBytes(generalTokens), tradingBytes), liveBytes);
    }

    private static List<byte[]> toBytes(List<String> tokens) {
        return tokens.stream().map(t -> t.getBytes(StandardCharsets.UTF_8)).toList();
    }

    private static List<byte[]> concat(List<byte[]> a, List<byte[]> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        List<byte[]> merged = new ArrayList<>(a.size() + b.size());
        merged.addAll(a);
        merged.addAll(b);
        return List.copyOf(merged);
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = resolvePath(request);
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                || path.startsWith("/auth/saxo/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        String token = (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7))
                ? auth.substring(7).trim() : null;

        List<byte[]> required = requiresTradingToken(request) ? tradingOrLiveTokens : allTokens;

        if (token == null || !containsToken(required, token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        request.setAttribute(TOKEN_ATTR, token);
        chain.doFilter(request, response);
    }

    /** Constant-time membership check — iterates all entries regardless of an early match. */
    private static boolean containsToken(List<byte[]> tokens, String presented) {
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        boolean found = false;
        for (byte[] t : tokens) {
            if (MessageDigest.isEqual(t, candidate)) {
                found = true;
            }
        }
        return found;
    }

    /**
     * Resolves the request path the same way the downstream controller sees it: the
     * container-decoded, normalized {@code servletPath}. Falls back to URL-decoding
     * {@code requestURI} when servletPath is unavailable (e.g. some test/mock setups).
     * The fallback is best-effort: on decode failure the raw (still percent-encoded)
     * URI is returned so callers can detect and fail closed on it.
     */
    private static String resolvePath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
        String uri = request.getRequestURI();
        if (uri == null) return "";
        try {
            return URLDecoder.decode(uri, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return uri;
        }
    }

    /**
     * Determines whether the request requires the trading ∪ live token set, i.e. the
     * restrictive set. This is true for /tools/{name} paths where the (decoded) name
     * resolves to a trading-namespace tool, OR where the name cannot be cleanly resolved
     * at all (unknown tool, still percent-encoded after decoding, empty) — fail closed:
     * an unresolvable name never falls back to the more permissive general token set.
     * Everything outside /tools/** (e.g. /mcp) is unaffected and uses the permissive set.
     */
    private boolean requiresTradingToken(HttpServletRequest request) {
        String path = resolvePath(request);
        if (!path.startsWith("/tools/")) {
            return false;
        }
        String name = path.substring("/tools/".length());
        int slash = name.indexOf('/');
        if (slash >= 0) {
            name = name.substring(0, slash);
        }
        if (name.isEmpty() || name.contains("%")) {
            // Unresolvable (still encoded, or malformed) → fail closed.
            return true;
        }
        try {
            AgoraTool tool = registry.get(name);
            return "trading".equals(tool.namespace());
        } catch (ToolNotFoundException e) {
            // Unknown tool name → fail closed (require the most privileged token set).
            return true;
        }
        // Any other exception propagates → request denied by the filter chain never being reached.
    }
}
