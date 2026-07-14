package de.visterion.agora.tools;

import de.visterion.agora.data.Instrument;
import de.visterion.agora.data.InstrumentResolver;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import de.visterion.agora.research.fundamentals.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GetFundamentalConceptsToolTest {
    @Test
    void emitsConceptsWithUnitAndSource() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        InstrumentResolver resolver = mock(InstrumentResolver.class);
        when(resolver.resolve("SAP.DE")).thenReturn(Instrument.raw("SAP.DE"));
        when(router.facts(any())).thenReturn(new SourceResult(Map.of(
            FundamentalConcept.TOTAL_ASSETS, new ConceptSeries("EUR",
                List.of(new ConceptDatapoint(null, LocalDate.parse("2025-12-31"), BigDecimal.valueOf(70362000000L), 2025, "FY", null, LocalDate.parse("2025-12-31"))))),
            AbsenceSemantics.SPARSE));
        var tool = new GetFundamentalConceptsTool(router, resolver);
        assertThat(tool.name()).isEqualTo("get_fundamental_concepts");
        ObjectMapper m = new ObjectMapper();
        JsonNode out = tool.call(m.readTree("{\"symbol\":\"SAP.DE\"}")).output();
        assertThat(out.path("source").asString("")).isEqualTo("SPARSE");
        assertThat(out.path("concepts").path("TOTAL_ASSETS").path("unit").asString("")).isEqualTo("EUR");
        assertThat(out.path("concepts").path("TOTAL_ASSETS").path("datapoints").get(0).path("value").decimalValue())
            .isEqualByComparingTo("70362000000");
    }

    @Test
    void datapointsCarryPeriodStartAndFiled() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        InstrumentResolver resolver = mock(InstrumentResolver.class);
        when(resolver.resolve("AAPL")).thenReturn(Instrument.raw("AAPL"));
        // Instant fact (periodStart null) + duration fact (periodStart set); both carry a filed date.
        when(router.facts(any())).thenReturn(new SourceResult(Map.of(
            FundamentalConcept.TOTAL_ASSETS, new ConceptSeries("USD", List.of(
                new ConceptDatapoint(null, LocalDate.parse("2023-09-30"), BigDecimal.valueOf(352583000000L), 2023, "FY", "10-K", LocalDate.parse("2023-11-03")),
                new ConceptDatapoint(LocalDate.parse("2023-01-01"), LocalDate.parse("2023-09-30"), BigDecimal.valueOf(383285000000L), 2023, "FY", "10-K", LocalDate.parse("2023-11-03"))))),
            AbsenceSemantics.SPARSE));
        var tool = new GetFundamentalConceptsTool(router, resolver);
        JsonNode dps = tool.call(new ObjectMapper().readTree("{\"symbol\":\"AAPL\"}"))
            .output().path("concepts").path("TOTAL_ASSETS").path("datapoints");
        // Instant fact: periodStart is JSON null, filed carries the date string.
        assertThat(dps.get(0).has("periodStart")).isTrue();
        assertThat(dps.get(0).path("periodStart").isNull()).isTrue();
        assertThat(dps.get(0).path("filed").asString("")).isEqualTo("2023-11-03");
        // Duration fact: periodStart carries the date string.
        assertThat(dps.get(1).path("periodStart").asString("")).isEqualTo("2023-01-01");
        assertThat(dps.get(1).path("filed").asString("")).isEqualTo("2023-11-03");
    }

    @Test
    void blankSymbolUnavailable() {
        var tool = new GetFundamentalConceptsTool(mock(FundamentalsRouter.class), mock(InstrumentResolver.class));
        assertThat(tool.call(new ObjectMapper().readTree("{}")).available()).isFalse();
    }
}
