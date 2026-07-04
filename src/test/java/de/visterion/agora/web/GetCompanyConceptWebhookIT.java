package de.visterion.agora.web;

import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarCikResolver;
import de.visterion.agora.fetch.edgar.EdgarService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "agora.auth.tokens=test-token")
class GetCompanyConceptWebhookIT {

    @LocalServerPort int port;

    @TestConfiguration
    static class StubConfig {
        @Bean @Primary
        EdgarService stubEdgarService(EdgarCikResolver cik) {
            return new EdgarService(RestClient.create(), cik, 1L, System::currentTimeMillis) {
                @Override public String resolveCik(String symbol, String c) { return "0000320193"; }
                @Override public ConceptSeries companyConcept(String symbol, String c, String taxonomy, String tag) {
                    // Sentinel form a real EDGAR call could never return
                    return new ConceptSeries("SENTINEL-UNIT", List.of(new ConceptDatapoint(
                            LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"),
                            new BigDecimal("365000000000"), 2024, "FY", "SENTINEL-10-K",
                            LocalDate.parse("2025-01-31"))));
                }
            };
        }
    }

    @Test void getCompanyConceptOverWebhookWithBearer() {
        RestClient http = RestClient.create();
        ResponseEntity<String> resp = http.post()
                .uri("http://localhost:" + port + "/tools/get_company_concept")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"symbol\":\"AAPL\",\"tag\":\"Assets\"}")
                .retrieve().toEntity(String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("SENTINEL-UNIT").contains("SENTINEL-10-K");
    }

    @Test void rejectsWithoutBearer() {
        RestClient http = RestClient.create();
        try {
            http.post().uri("http://localhost:" + port + "/tools/get_company_concept")
                    .contentType(MediaType.APPLICATION_JSON).body("{\"symbol\":\"AAPL\",\"tag\":\"Assets\"}")
                    .retrieve().toBodilessEntity();
            fail("expected 401");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
    }
}
