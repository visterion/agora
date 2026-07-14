package de.visterion.agora.observability;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderLogRedactorTest {

    @Test
    void redactsSecretQueryParamsKeepsOthers() {
        String q = "symbol=SAP.DE&crumb=abc123&type=annualEbit&token=SEKRET&apikey=K";
        String r = ProviderLogRedactor.redactQuery(q);
        assertThat(r).contains("symbol=SAP.DE").contains("type=annualEbit");
        assertThat(r).doesNotContain("abc123").doesNotContain("SEKRET").doesNotContain("apikey=K");
        assertThat(r).contains("crumb=***").contains("token=***").contains("apikey=***");
    }

    @Test
    void redactsSecretHeaderValues() {
        assertThat(ProviderLogRedactor.redactHeaderValue("Authorization", "Bearer xyz")).isEqualTo("***");
        assertThat(ProviderLogRedactor.redactHeaderValue("X-Finnhub-Token", "abc")).isEqualTo("***");
        assertThat(ProviderLogRedactor.redactHeaderValue("APCA-API-SECRET-KEY", "s")).isEqualTo("***");
        assertThat(ProviderLogRedactor.redactHeaderValue("Authorization", "Basic dXNlcg==")).isEqualTo("***");
        assertThat(ProviderLogRedactor.redactHeaderValue("Accept", "application/json")).isEqualTo("application/json");
    }

    @Test
    void redactsRefreshTokenInFormAndJsonBody() {
        assertThat(ProviderLogRedactor.redactBody("grant_type=refresh_token&refresh_token=SEKRET"))
                .contains("grant_type=refresh_token").doesNotContain("refresh_token=SEKRET").contains("refresh_token=***");
        assertThat(ProviderLogRedactor.redactBody("{\"access_token\":\"a\",\"refresh_token\":\"SEKRET\"}"))
                .contains("\"access_token\":\"a\"").doesNotContain("SEKRET").contains("\"refresh_token\":\"***\"");
    }

    @Test
    void redactsEmailInUserAgent() {
        assertThat(ProviderLogRedactor.redactUserAgent("Agora research bot ops@example.com"))
                .isEqualTo("Agora research bot ***@***");
    }

    @Test
    void nullSafe() {
        assertThat(ProviderLogRedactor.redactQuery(null)).isNull();
        assertThat(ProviderLogRedactor.redactBody(null)).isNull();
    }
}
