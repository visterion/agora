package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarSearchService;
import de.visterion.agora.fetch.edgar.FilingHit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class GetSearchFilingsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsHits() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.search(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                new FilingHit("SPNC", "Apple Spinco Inc.", "10-12B", LocalDate.parse("2025-05-02"),
                        "0000320193-25-000050",
                        "https://www.sec.gov/Archives/edgar/data/320193/000032019325000050/aapl-1012b.htm")));
        var args = mapper.createObjectNode();
        args.putArray("forms").add("10-12B");
        var r = new GetSearchFilingsTool(svc).call(args);
        assertThat(r.available()).isTrue();
        var hit = r.output().get("filings").get(0);
        assertThat(hit.get("ticker").asString()).isEqualTo("SPNC");
        assertThat(hit.get("company").asString()).isEqualTo("Apple Spinco Inc.");
        assertThat(hit.get("form").asString()).isEqualTo("10-12B");
        assertThat(hit.get("filedDate").asString()).isEqualTo("2025-05-02");
        assertThat(hit.get("url").asString()).contains("/Archives/edgar/data/320193/");
    }

    @Test void acceptsCsvStringForms() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.search(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        var r = new GetSearchFilingsTool(svc).call(mapper.createObjectNode().put("forms", "8-K,10-K"));
        assertThat(r.available()).isTrue();
    }

    @Test void missingFormsUnavailable() {
        assertThat(new GetSearchFilingsTool(Mockito.mock(EdgarSearchService.class))
                .call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.search(any(), any(), any(), any(), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR down", null));
        var args = mapper.createObjectNode();
        args.putArray("forms").add("8-K");
        assertThat(new GetSearchFilingsTool(svc).call(args).available()).isFalse();
    }

    @Test void namespaceIsGeneral() {
        assertThat(new GetSearchFilingsTool(Mockito.mock(EdgarSearchService.class)).namespace())
                .isEqualTo("general");
    }
}
