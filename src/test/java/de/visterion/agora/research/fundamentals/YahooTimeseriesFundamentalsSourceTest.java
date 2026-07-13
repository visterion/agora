package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class YahooTimeseriesFundamentalsSourceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/yahoo/" + name)) {
            return mapper.readTree(in);
        }
    }

    @Test
    void mapsSapDeConceptsAndSelectsViaAnnualFacts() throws Exception {
        YahooCrumbClient crumb = mock(YahooCrumbClient.class);
        when(crumb.timeseries(eq("SAP.DE"), anyString())).thenReturn(fixture("timeseries-SAP.DE.json"));
        var src = new YahooTimeseriesFundamentalsSource(crumb, 21600, () -> 0L);

        SourceResult r = src.facts(Instrument.raw("SAP.DE"));

        assertThat(r.semantics()).isEqualTo(AbsenceSemantics.SPARSE);
        ConceptSeries ta = r.series(FundamentalConcept.TOTAL_ASSETS);
        assertThat(ta.unit()).isEqualTo("EUR");
        // AnnualFacts must SELECT the instant row (periodStart null, fp "FY"):
        AnnualFacts af = AnnualFacts.ofInstant(ta);
        assertThat(af.hasCurrent()).isTrue();
        // income concept is a duration AnnualFacts.of picks:
        assertThat(AnnualFacts.of(r.series(FundamentalConcept.EBIT)).hasCurrent()).isTrue();
    }

    @Test
    void hongKongReportsCnyNotHkd() throws Exception {
        YahooCrumbClient crumb = mock(YahooCrumbClient.class);
        when(crumb.timeseries(eq("0700.HK"), anyString())).thenReturn(fixture("timeseries-0700.HK.json"));
        var src = new YahooTimeseriesFundamentalsSource(crumb, 21600, () -> 0L);
        assertThat(src.facts(Instrument.raw("0700.HK")).series(FundamentalConcept.TOTAL_ASSETS).unit())
            .isEqualTo("CNY");
    }

    @Test
    void isinResolvedViaSearch() throws Exception {
        YahooCrumbClient crumb = mock(YahooCrumbClient.class);
        when(crumb.searchIsin("DE0007164600")).thenReturn(Optional.of("SAP.DE"));
        when(crumb.timeseries(eq("SAP.DE"), anyString())).thenReturn(fixture("timeseries-SAP.DE.json"));
        var src = new YahooTimeseriesFundamentalsSource(crumb, 21600, () -> 0L);
        assertThat(src.facts(Instrument.raw("DE0007164600")).series(FundamentalConcept.TOTAL_ASSETS).datapoints())
            .isNotEmpty();
    }

    private YahooTimeseriesFundamentalsSource srcReturning(String json) throws Exception {
        YahooCrumbClient crumb = mock(YahooCrumbClient.class);
        when(crumb.timeseries(anyString(), anyString())).thenReturn(mapper.readTree(json));
        return new YahooTimeseriesFundamentalsSource(crumb, 21600, () -> 0L);
    }

    @Test
    void skipsPaddedNullRowsAndDoesNotReadAsZero() throws Exception {
        String json = "{\"timeseries\":{\"result\":[{\"meta\":{\"type\":[\"annualTotalAssets\"]},"
            + "\"annualTotalAssets\":[null,{\"asOfDate\":\"2025-12-31\",\"currencyCode\":\"EUR\",\"reportedValue\":{\"raw\":100}}]}]}}";
        var r = srcReturning(json).facts(Instrument.raw("X.DE"));
        assertThat(r.series(FundamentalConcept.TOTAL_ASSETS).datapoints()).hasSize(1); // padded null skipped, not a 0 row
    }

    @Test
    void mixedCurrencyKeepsLatestCurrencyOnly() throws Exception {
        String json = "{\"timeseries\":{\"result\":[{\"meta\":{\"type\":[\"annualTotalAssets\"]},"
            + "\"annualTotalAssets\":["
            + "{\"asOfDate\":\"2023-12-31\",\"currencyCode\":\"GBP\",\"reportedValue\":{\"raw\":90}},"
            + "{\"asOfDate\":\"2024-12-31\",\"currencyCode\":\"EUR\",\"reportedValue\":{\"raw\":100}}]}]}}";
        var s = srcReturning(json).facts(Instrument.raw("VOD.L")).series(FundamentalConcept.TOTAL_ASSETS);
        assertThat(s.unit()).isEqualTo("EUR");
        assertThat(s.datapoints()).hasSize(1); // GBP back-year dropped
    }

    @Test
    void transientFailureIsNotCached() throws Exception {
        YahooCrumbClient crumb = mock(YahooCrumbClient.class);
        when(crumb.timeseries(anyString(), anyString()))
            .thenThrow(new de.visterion.agora.data.MarketDataException(
                de.visterion.agora.data.MarketDataException.Kind.UNAVAILABLE, "429", null))
            .thenReturn(fixture("timeseries-SAP.DE.json"));
        var src = new YahooTimeseriesFundamentalsSource(crumb, 21600, () -> 0L);
        // first call throws (not cached); second call must re-fetch and succeed
        try { src.facts(Instrument.raw("SAP.DE")); } catch (de.visterion.agora.data.MarketDataException ignored) {}
        assertThat(src.facts(Instrument.raw("SAP.DE")).series(FundamentalConcept.TOTAL_ASSETS).datapoints()).isNotEmpty();
        verify(crumb, times(2)).timeseries(eq("SAP.DE"), anyString()); // proves the throw was not cached
    }
}
