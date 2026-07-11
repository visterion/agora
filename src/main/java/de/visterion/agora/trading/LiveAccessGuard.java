package de.visterion.agora.trading;

import de.visterion.agora.security.BearerTokenFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private final Set<String> liveReadonlyTokens;
    private final Supplier<String> callerToken;

    @Autowired
    public LiveAccessGuard(@Value("${agora.trading.live-tokens:}") String liveCsv,
                           @Value("${agora.trading.live-tokens-readonly:}") String readonlyCsv) {
        this(parseCsv(liveCsv), parseCsv(readonlyCsv), LiveAccessGuard::requestToken);
    }

    /** Back-compat convenience for existing tests/harness. */
    public LiveAccessGuard(Set<String> liveTokens, Supplier<String> callerToken) {
        this(liveTokens, Set.of(), callerToken);
    }

    public LiveAccessGuard(Set<String> liveTokens, Set<String> liveReadonlyTokens,
                           Supplier<String> callerToken) {
        this.liveTokens = liveTokens;
        this.liveReadonlyTokens = liveReadonlyTokens;
        this.callerToken = callerToken;
    }

    public boolean hasLiveAccess() { return matches(liveTokens); }

    /** Full live access OR a read-only live token: may see/read LIVE, never trade it. */
    public boolean hasLiveReadAccess() { return hasLiveAccess() || matches(liveReadonlyTokens); }

    /** Visibility/read: paper for everyone, live with read access. */
    public boolean canSee(RegisteredConnection c) {
        return c.config().getEnvironment() != ConnectionConfig.Environment.LIVE || hasLiveReadAccess();
    }

    /** Mutation: paper for everyone, live ONLY with a full live token. */
    public boolean canTrade(RegisteredConnection c) {
        return c.config().getEnvironment() != ConnectionConfig.Environment.LIVE || hasLiveAccess();
    }

    private boolean matches(Set<String> tokens) {
        String token = callerToken.get();
        if (token == null) return false;
        byte[] candidate = token.getBytes(StandardCharsets.UTF_8);
        boolean found = false;
        for (String t : tokens) {
            if (MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), candidate)) found = true;
        }
        return found;
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
