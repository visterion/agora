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
    void doesNotOverRedactFieldNameThatIsASuffixOfATargetField() {
        // "token" is a redaction target, but "access_token" is NOT -- the field-name
        // boundary check must not match "token=" inside "access_token=".
        assertThat(ProviderLogRedactor.redactBody("access_token=abc123"))
                .isEqualTo("access_token=abc123");
    }

    @Test
    void redactsMixedCaseQueryParam() {
        assertThat(ProviderLogRedactor.redactQuery("apiKey=K")).isEqualTo("apiKey=***");
    }

    @Test
    void redactsApcaApiKeyIdHeader() {
        assertThat(ProviderLogRedactor.redactHeaderValue("APCA-API-KEY-ID", "K")).isEqualTo("***");
    }

    @Test
    void redactsFormBodySecretFields() {
        assertThat(ProviderLogRedactor.redactBody("client_secret=S")).isEqualTo("client_secret=***");
        assertThat(ProviderLogRedactor.redactBody("password=P")).isEqualTo("password=***");
        assertThat(ProviderLogRedactor.redactBody("crumb=C")).isEqualTo("crumb=***");
    }

    @Test
    void redactsEmailInUserAgent() {
        assertThat(ProviderLogRedactor.redactUserAgent("Agora research bot ops@example.com"))
                .isEqualTo("Agora research bot ***@***");
    }

    @Test
    void redactHeaderValueMasksEmailInUserAgentHeader() {
        // EDGAR's User-Agent carries a real contact email (SEC requirement); it must never
        // reach the provider_call log unredacted -- redactHeaderValue is the single choke
        // point the interceptor calls for every header, so the UA email masking must happen
        // there, not just in the untested-in-practice redactUserAgent() helper.
        String redacted = ProviderLogRedactor.redactHeaderValue("User-Agent", "agora agora@visterion.de");
        assertThat(redacted).isEqualTo("agora ***@***");
        assertThat(redacted).doesNotContain("agora@visterion.de");
    }

    @Test
    void redactHeaderValueLowercaseUserAgentAlsoMasksEmail() {
        assertThat(ProviderLogRedactor.redactHeaderValue("user-agent", "agora agora@visterion.de"))
                .isEqualTo("agora ***@***");
    }

    @Test
    void nullSafe() {
        assertThat(ProviderLogRedactor.redactQuery(null)).isNull();
        assertThat(ProviderLogRedactor.redactBody(null)).isNull();
    }
}
