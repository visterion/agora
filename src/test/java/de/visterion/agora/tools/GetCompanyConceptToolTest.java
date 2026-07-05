package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetCompanyConceptToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsSeries() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(any(), any())).thenReturn("0000320193");
        when(svc.companyConcept(any(), any(), any(), any())).thenReturn(new EdgarService.ConceptSeries("USD",
                List.of(new ConceptDatapoint(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"),
                        new BigDecimal("365000000000"), 2024, "FY", "10-K", LocalDate.parse("2025-01-31")))));
        var args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.put("tag", "Assets");
        var r = new GetCompanyConceptTool(svc).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("cik").asString()).isEqualTo("0000320193");
        assertThat(r.output().get("taxonomy").asString()).isEqualTo("us-gaap");
        assertThat(r.output().get("tag").asString()).isEqualTo("Assets");
        assertThat(r.output().get("unit").asString()).isEqualTo("USD");
        var d = r.output().get("datapoints").get(0);
        assertThat(d.get("periodEnd").asString()).isEqualTo("2024-12-31");
        assertThat(new BigDecimal(d.get("value").asString())).isEqualByComparingTo("365000000000");
        assertThat(d.get("fiscalYear").asInt()).isEqualTo(2024);
        assertThat(d.get("form").asString()).isEqualTo("10-K");
    }

    @Test void symbolPathPassesOriginalArgsToServiceNotResolvedCik() {
        // Regression: the tool must pass the ORIGINAL symbol/cik to companyConcept and let the
        // service resolve the CIK exactly once. Passing the already-resolved CIK into the
        // symbol position makes the service re-resolve it as a ticker → "no CIK for 0000320193".
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik("AAPL", null)).thenReturn("0000320193");
        when(svc.companyConcept(eq("AAPL"), isNull(), eq("us-gaap"), eq("Revenues")))
                .thenReturn(new EdgarService.ConceptSeries("USD",
                        List.of(new ConceptDatapoint(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"),
                                new BigDecimal("391000000000"), 2024, "FY", "10-K", LocalDate.parse("2025-01-31")))));
        var args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.put("tag", "Revenues");
        var r = new GetCompanyConceptTool(svc).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("cik").asString()).isEqualTo("0000320193");
        assertThat(r.output().get("unit").asString()).isEqualTo("USD");
        verify(svc).companyConcept("AAPL", null, "us-gaap", "Revenues");
        verify(svc, never()).companyConcept(eq("0000320193"), any(), any(), any());
    }

    @Test void usesCikArgAsTaxonomyDefault() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(any(), any())).thenReturn("0000320193");
        when(svc.companyConcept(any(), any(), any(), any()))
                .thenReturn(new EdgarService.ConceptSeries(null, List.of()));
        var args = mapper.createObjectNode();
        args.put("cik", "320193");
        args.put("tag", "Assets");
        var r = new GetCompanyConceptTool(svc).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("taxonomy").asString()).isEqualTo("us-gaap");
    }

    @Test void missingSymbolAndCikUnavailable() {
        var args = mapper.createObjectNode();
        args.put("tag", "Assets");
        assertThat(new GetCompanyConceptTool(Mockito.mock(EdgarService.class)).call(args).available()).isFalse();
    }

    @Test void missingTagUnavailable() {
        var args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        assertThat(new GetCompanyConceptTool(Mockito.mock(EdgarService.class)).call(args).available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no CIK", null));
        var args = mapper.createObjectNode();
        args.put("symbol", "ZZZZ");
        args.put("tag", "Assets");
        assertThat(new GetCompanyConceptTool(svc).call(args).available()).isFalse();
    }

    @Test void namespaceIsGeneral() {
        assertThat(new GetCompanyConceptTool(Mockito.mock(EdgarService.class)).namespace()).isEqualTo("general");
    }
}
