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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

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

    @Test void fromAfterToUnavailable() {
        var args = mapper.createObjectNode();
        args.putArray("forms").add("8-K");
        args.put("from", "2025-05-10");
        args.put("to", "2025-05-01");
        assertThat(new GetSearchFilingsTool(Mockito.mock(EdgarSearchService.class)).call(args).available()).isFalse();
    }

    @Test void nonIntegralLimitUnavailable() {
        var args = mapper.createObjectNode();
        args.putArray("forms").add("8-K");
        args.put("limit", 2.5);
        assertThat(new GetSearchFilingsTool(Mockito.mock(EdgarSearchService.class)).call(args).available()).isFalse();
    }

    @Test void fullPageMarksTruncated() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.search(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                new FilingHit("A", "A Inc.", "8-K", LocalDate.parse("2025-05-02"), "acc", "url"),
                new FilingHit("B", "B Inc.", "8-K", LocalDate.parse("2025-05-02"), "acc2", "url2")));
        var args = mapper.createObjectNode();
        args.putArray("forms").add("8-K");
        args.put("limit", 2);
        var r = new GetSearchFilingsTool(svc).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("truncated").asBoolean()).isTrue();
    }

    @Test void oversizedLimitIsClampedToMax() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.search(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        var args = mapper.createObjectNode();
        args.putArray("forms").add("8-K");
        args.put("limit", 100_000);
        new GetSearchFilingsTool(svc).call(args);
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(svc).search(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(1000);
    }

    /** No explicit limit must still reach the service as the documented default, not as the max. */
    @Test void absentLimitUsesDefault() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.search(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        var args = mapper.createObjectNode();
        args.putArray("forms").add("8-K");
        new GetSearchFilingsTool(svc).call(args);
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(svc).search(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(100);
    }

    // A3: truncation must stay exact at the NEW bound. An off-by-one here re-creates the silent
    // degradation this change removes: a full 1000-row page reported as a complete window.
    @Test void resultCutAtNewMaxReportsTruncated() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.search(any(), any(), any(), any(), anyInt())).thenReturn(hits(1000));
        var args = mapper.createObjectNode();
        args.putArray("forms").add("8-K");
        args.put("limit", 1000);
        var r = new GetSearchFilingsTool(svc).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("filings")).hasSize(1000);
        assertThat(r.output().get("truncated").asBoolean()).isTrue();
    }

    @Test void resultBelowNewMaxReportsNotTruncated() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.search(any(), any(), any(), any(), anyInt())).thenReturn(hits(999));
        var args = mapper.createObjectNode();
        args.putArray("forms").add("8-K");
        args.put("limit", 1000);
        var r = new GetSearchFilingsTool(svc).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("filings")).hasSize(999);
        assertThat(r.output().get("truncated").asBoolean()).isFalse();
    }

    @Test void schemaDocumentsTheNewMax() {
        String description = new GetSearchFilingsTool(Mockito.mock(EdgarSearchService.class)).inputSchema()
                .path("properties").path("limit").path("description").asString();
        assertThat(description).contains("max 1000");
    }

    private static List<FilingHit> hits(int n) {
        List<FilingHit> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new FilingHit("T" + i, "Co " + i, "8-K", LocalDate.parse("2025-05-02"),
                    "acc-" + i, "https://www.sec.gov/Archives/edgar/data/1/" + i + ".htm"));
        }
        return out;
    }
}
