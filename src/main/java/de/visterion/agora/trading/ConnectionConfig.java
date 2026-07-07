package de.visterion.agora.trading;

import java.util.LinkedHashMap;
import java.util.Map;

/** One named trading connection: provider × environment × exactly one account. */
public class ConnectionConfig {

    public enum Environment { PAPER, LIVE }

    private String provider;
    private Environment environment;
    private String baseUrl;
    private String keyId;
    private String secret;
    /** Provider-specific extras (e.g. an account key for brokers with multi-account logins). */
    private Map<String, String> extra = new LinkedHashMap<>();

    /** A connection is active iff both credentials are set. */
    public boolean active() {
        return keyId != null && !keyId.isBlank() && secret != null && !secret.isBlank();
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment environment) { this.environment = environment; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public Map<String, String> getExtra() { return extra; }
    public void setExtra(Map<String, String> extra) { this.extra = extra; }
}
