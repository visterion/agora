package de.visterion.agora.web;

import de.visterion.agora.fetch.edgar.EdgarCikResolver;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.FilingRef;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "agora.auth.tokens=test-token")
class GetFilingsWebhookIT {

    @LocalServerPort int port;

    @TestConfiguration
    static class StubConfig {
        @Bean @Primary
        EdgarService stubEdgarService(EdgarCikResolver cik) {
            return new EdgarService(RestClient.create(), cik, 1L, System::currentTimeMillis) {
                @Override public String resolveCik(String symbol, String c) { return "0000320193"; }
                @Override public List<FilingRef> filings(String symbol, String c, String formType, LocalDate from, LocalDate to, int limit) {
                    // Sentinel accession a real EDGAR call could never return
                    return List.of(new FilingRef("SENTINEL-25-000001", "8-K",
                            LocalDate.parse("2025-05-02"), LocalDate.parse("2025-05-01"),
                            "sentinel.htm", "https://www.sec.gov/Archives/edgar/data/1/SENTINEL/sentinel.htm"));
                }
            };
        }
    }

    @Test void getFilingsOverWebhookWithBearer() {
        RestClient http = RestClient.create();
        ResponseEntity<String> resp = http.post()
                .uri("http://localhost:" + port + "/tools/get_filings")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"symbol\":\"AAPL\"}")
                .retrieve().toEntity(String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("SENTINEL-25-000001").contains("\"form\":\"8-K\"");
    }

    @Test void rejectsWithoutBearer() {
        RestClient http = RestClient.create();
        try {
            http.post().uri("http://localhost:" + port + "/tools/get_filings")
                    .contentType(MediaType.APPLICATION_JSON).body("{\"symbol\":\"AAPL\"}")
                    .retrieve().toBodilessEntity();
            fail("expected 401");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
    }
}
