package de.visterion.agora.trading;

import de.visterion.agora.security.BearerTokenFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Decides whether the current caller may see/use LIVE connections.
 * The caller's token is read from the request attribute set by BearerTokenFilter.
 * Single scoping source for router error lists, the live gate, and list_connections.
 */
@Component
public class LiveAccessGuard {

    private final Set<String> liveTokens;
    private final Supplier<String> callerToken;

    @Autowired
    public LiveAccessGuard(@Value("${agora.trading.live-tokens:}") String liveCsv) {
        this(parseCsv(liveCsv), LiveAccessGuard::requestToken);
    }

    public LiveAccessGuard(Set<String> liveTokens, Supplier<String> callerToken) {
        this.liveTokens = liveTokens;
        this.callerToken = callerToken;
    }

    public boolean hasLiveAccess() {
        String token = callerToken.get();
        return token != null && liveTokens.contains(token);
    }

    /** Visibility: paper is visible to every trading caller; live only with live access. */
    public boolean canSee(RegisteredConnection c) {
        return c.config().getEnvironment() != ConnectionConfig.Environment.LIVE || hasLiveAccess();
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String requestToken() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        Object v = attrs.getAttribute(BearerTokenFilter.TOKEN_ATTR, RequestAttributes.SCOPE_REQUEST);
        return (v instanceof String s) ? s : null;
    }
}
