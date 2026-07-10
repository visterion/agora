package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.FilingRef;
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

class GetFilingsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsFilings() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(any(), any())).thenReturn("0000320193");
        when(svc.filings(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                new FilingRef("0000320193-25-000050", "8-K", LocalDate.parse("2025-05-02"),
                        LocalDate.parse("2025-05-01"), "aapl-8k.htm",
                        "https://www.sec.gov/Archives/edgar/data/320193/000032019325000050/aapl-8k.htm")));
        var r = new GetFilingsTool(svc).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("cik").asString()).isEqualTo("0000320193");
        assertThat(r.output().get("filings").get(0).get("form").asString()).isEqualTo("8-K");
    }

    @Test void missingSymbolAndCikUnavailable() {
        assertThat(new GetFilingsTool(Mockito.mock(EdgarService.class))
                .call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no CIK", null));
        assertThat(new GetFilingsTool(svc).call(mapper.createObjectNode().put("symbol", "ZZZZ")).available()).isFalse();
    }

    @Test void fromAfterToUnavailable() {
        var args = mapper.createObjectNode().put("symbol", "AAPL").put("from", "2025-05-10").put("to", "2025-05-01");
        assertThat(new GetFilingsTool(Mockito.mock(EdgarService.class)).call(args).available()).isFalse();
    }

    @Test void nonIntegralLimitUnavailable() {
        var args = mapper.createObjectNode().put("symbol", "AAPL").put("limit", 2.5);
        assertThat(new GetFilingsTool(Mockito.mock(EdgarService.class)).call(args).available()).isFalse();
    }

    @Test void fullPageMarksTruncated() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(any(), any())).thenReturn("0000320193");
        when(svc.filings(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                new FilingRef("acc1", "8-K", LocalDate.parse("2025-05-02"), LocalDate.parse("2025-05-01"), "doc1", "url1"),
                new FilingRef("acc2", "8-K", LocalDate.parse("2025-05-02"), LocalDate.parse("2025-05-01"), "doc2", "url2")));
        var args = mapper.createObjectNode().put("symbol", "AAPL").put("limit", 2);
        var r = new GetFilingsTool(svc).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("truncated").asBoolean()).isTrue();
    }

    @Test void oversizedLimitIsClampedTo100() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(any(), any())).thenReturn("0000320193");
        when(svc.filings(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        var args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.put("limit", 100_000);
        new GetFilingsTool(svc).call(args);
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(svc).filings(any(), any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(100);
    }
}
