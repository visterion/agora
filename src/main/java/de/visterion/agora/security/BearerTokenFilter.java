package de.visterion.agora.security;

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
import java.util.List;

/** Guards /tools/** and /mcp/** with a shared bearer token (one accepted token
 *  per consumer). /actuator/health stays public. */
@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private final List<String> acceptedTokens;

    @Autowired
    public BearerTokenFilter(@Value("${agora.auth.tokens:}") String csv) {
        this((csv == null || csv.isBlank())
                ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    BearerTokenFilter(List<String> acceptedTokens) {
        this.acceptedTokens = acceptedTokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : null;
        if (token == null || !acceptedTokens.contains(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
