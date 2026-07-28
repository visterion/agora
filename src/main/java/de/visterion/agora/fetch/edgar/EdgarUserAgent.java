package de.visterion.agora.fetch.edgar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared guard for the EDGAR contact User-Agent header. SEC requires every request to carry a
 * contact User-Agent and returns HTTP 403 without one; the configured value must come from the
 * {@code AGORA_DATA_EDGAR_USER_AGENT} environment variable (the YAML default is intentionally
 * blank — a real contact address must never be committed to this public repo). Missing
 * configuration is a loud WARN, never a thrown exception: it must not break local dev or
 * Spring-context tests where the env var is unset.
 */
final class EdgarUserAgent {

    private static final Logger log = LoggerFactory.getLogger(EdgarUserAgent.class);

    private EdgarUserAgent() {
    }

    /**
     * Logs a WARN when {@code userAgent} is blank (null or whitespace-only) and returns it
     * unchanged. Pass-through shape so it can be embedded directly in a constructor-chaining
     * {@code this(...)} call's argument list, where {@code this(...)} must remain the first
     * statement and no separate check statement can precede it.
     */
    static String checked(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            log.warn("AGORA_DATA_EDGAR_USER_AGENT is not set: SEC will reject EDGAR requests with HTTP 403");
        }
        return userAgent;
    }
}
