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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Guards /tools/** and /mcp/** with bearer tokens.
 * <ul>
 *   <li>General tools (and /mcp, unknown paths) → accept general ∪ trading tokens.</li>
 *   <li>Trading tools (/tools/{name} where namespace()=="trading") → accept only trading tokens.</li>
 *   <li>/actuator/health → public (no token required).</li>
 * </ul>
 */
@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private final List<String> generalTokens;
    private final List<String> tradingTokens;
    private final ToolRegistry registry;

    @Autowired
    public BearerTokenFilter(
            @Value("${agora.auth.tokens:}") String general,
            @Value("${agora.trading.tokens:}") String trading,
            ToolRegistry registry) {
        this(parseCsv(general), parseCsv(trading), registry);
    }

    BearerTokenFilter(List<String> generalTokens, List<String> tradingTokens, ToolRegistry registry) {
        this.generalTokens = generalTokens;
        this.tradingTokens = tradingTokens;
        this.registry = registry;
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : null;

        Set<String> required = requiredTokens(request.getRequestURI());

        if (token == null || !required.contains(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Determines which token set is accepted for the given path.
     * /tools/{name} where name resolves to a trading-namespace tool → trading only.
     * Everything else → general ∪ trading.
     */
    private Set<String> requiredTokens(String path) {
        String toolName = extractToolName(path);
        if (toolName != null && isTradingTool(toolName)) {
            return new HashSet<>(tradingTokens);
        }
        // general ∪ trading
        Set<String> all = new HashSet<>(generalTokens);
        all.addAll(tradingTokens);
        return all;
    }

    /** Returns the tool name from a /tools/{name} path, or null if path doesn't match. */
    private static String extractToolName(String path) {
        if (path == null || !path.startsWith("/tools/")) return null;
        String rest = path.substring("/tools/".length());
        if (rest.isEmpty()) return null;
        // strip any trailing slash or further path segments
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private boolean isTradingTool(String name) {
        try {
            AgoraTool tool = registry.get(name);
            return "trading".equals(tool.namespace());
        } catch (ToolNotFoundException e) {
            // Unknown tool → not a trading tool; general ∪ trading tokens accepted
            return false;
        }
        // Any other exception propagates → request denied (fail-closed)
    }
}
