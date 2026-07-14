package de.visterion.agora.observability;

import java.util.Set;
import java.util.regex.Pattern;

/** Pure, stateless secret-scrubbing for provider-call logging. No Spring, no state. */
public final class ProviderLogRedactor {

    private static final String MASK = "***";
    private static final Set<String> SECRET_HEADERS = Set.of(
            "authorization", "x-finnhub-token", "apca-api-key-id", "apca-api-secret-key");
    private static final Set<String> SECRET_QUERY_LOWER = Set.of("token", "crumb", "apikey");
    private static final Set<String> SECRET_BODY_FIELDS = Set.of(
            "refresh_token", "client_secret", "password", "token", "crumb");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private ProviderLogRedactor() {}

    public static String redactQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return rawQuery;
        String[] pairs = rawQuery.split("&");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) sb.append('&');
            String p = pairs[i];
            int eq = p.indexOf('=');
            String key = eq >= 0 ? p.substring(0, eq) : p;
            if (SECRET_QUERY_LOWER.contains(key.toLowerCase())) {
                sb.append(key).append('=').append(MASK);
            } else {
                sb.append(p);
            }
        }
        return sb.toString();
    }

    public static String redactHeaderValue(String name, String value) {
        if (name == null || value == null) return value;
        if (SECRET_HEADERS.contains(name.toLowerCase())) return MASK;
        if ("user-agent".equals(name.toLowerCase())) return redactUserAgent(value);
        String v = value.trim();
        if (v.startsWith("Bearer ") || v.startsWith("Basic ") || v.startsWith("apikey ")) return MASK;
        return value;
    }

    public static String redactBody(String body) {
        if (body == null || body.isEmpty()) return body;
        String out = body;
        for (String f : SECRET_BODY_FIELDS) {
            // JSON: "field":"value"
            out = out.replaceAll("(\"" + Pattern.quote(f) + "\"\\s*:\\s*\")[^\"]*(\")", "$1" + MASK + "$2");
            // form: field=value  (value = up to & or end); field must be at start-of-string or after &
            out = out.replaceAll("(^|&)(" + Pattern.quote(f) + "=)[^&]*", "$1$2" + MASK);
        }
        return out;
    }

    public static String redactUserAgent(String ua) {
        if (ua == null) return null;
        return EMAIL.matcher(ua).replaceAll("***@***");
    }
}
