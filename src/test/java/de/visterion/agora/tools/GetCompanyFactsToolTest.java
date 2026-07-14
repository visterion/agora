package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetCompanyFactsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static ConceptDatapoint dp(String periodEnd, String value, String filed) {
        return new ConceptDatapoint(null, LocalDate.parse(periodEnd), new BigDecimal(value),
                2023, "FY", "10-K", LocalDate.parse(filed));
    }

    /** All three concepts come from ONE companyfacts fetch, keyed by tag. */
    private static EdgarService.CompanyFacts facts() {
        return new EdgarService.CompanyFacts(Map.of(
                "Assets", new EdgarService.ConceptSeries("USD", List.of(dp("2023-09-30", "352583000000", "2023-11-03"))),
                "LiabilitiesCurrent", new EdgarService.ConceptSeries("USD", List.of(dp("2023-09-30", "145308000000", "2023-11-03"))),
                "RetainedEarnings", new EdgarService.ConceptSeries("USD", List.of(dp("2023-09-30", "-214000000", "2023-11-03")))));
    }

    @Test void returnsOnlyRequestedTags_shapeMatchesCompanyConcept() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik("AAPL", null)).thenReturn("0000320193");
        when(svc.companyFacts("AAPL", null)).thenReturn(facts());
        var args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.putArray("tags").add("Assets").add("LiabilitiesCurrent");

        var r = new GetCompanyFactsTool(svc).call(args);

        assertThat(r.available()).isTrue();
        assertThat(r.output().get("cik").asString()).isEqualTo("0000320193");
        assertThat(r.output().get("taxonomy").asString()).isEqualTo("us-gaap");

        JsonNode out = r.output().get("facts");
        // Only requested tags — RetainedEarnings was NOT requested, so it must be absent.
        assertThat(out.propertyNames()).containsExactlyInAnyOrder("Assets", "LiabilitiesCurrent");

        JsonNode assets = out.get("Assets");
        assertThat(assets.get("unit").asString()).isEqualTo("USD");
        JsonNode d = assets.get("datapoints").get(0);
        // Same per-datapoint shape as get_company_concept, incl. the `filed` field Dracul relies on.
        assertThat(d.get("periodEnd").asString()).isEqualTo("2023-09-30");
        assertThat(new BigDecimal(d.get("value").asString())).isEqualByComparingTo("352583000000");
        assertThat(d.get("fiscalYear").asInt()).isEqualTo(2023);
        assertThat(d.get("fiscalPeriod").asString()).isEqualTo("FY");
        assertThat(d.get("form").asString()).isEqualTo("10-K");
        assertThat(d.get("filed").asString()).isEqualTo("2023-11-03");

        assertThat(out.get("LiabilitiesCurrent").get("datapoints").get(0).get("filed").asString())
                .isEqualTo("2023-11-03");
    }

    @Test void fetchesCompanyFactsOnceForManyTags() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(any(), any())).thenReturn("0000320193");
        when(svc.companyFacts(any(), any())).thenReturn(facts());
        var args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.putArray("tags").add("Assets").add("LiabilitiesCurrent").add("RetainedEarnings");

        var r = new GetCompanyFactsTool(svc).call(args);

        assertThat(r.available()).isTrue();
        // The whole point: N tags → ONE service (and thus one upstream) call.
        verify(svc, times(1)).companyFacts(any(), any());
    }

    @Test void passesOriginalArgsToService_notResolvedCik() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik("AAPL", null)).thenReturn("0000320193");
        when(svc.companyFacts(eq("AAPL"), isNull())).thenReturn(facts());
        var args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.putArray("tags").add("Assets");

        var r = new GetCompanyFactsTool(svc).call(args);

        assertThat(r.available()).isTrue();
        verify(svc).companyFacts("AAPL", null);
    }

    @Test void cikInputWorks() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(isNull(), eq("320193"))).thenReturn("0000320193");
        when(svc.companyFacts(isNull(), eq("320193"))).thenReturn(facts());
        var args = mapper.createObjectNode();
        args.put("cik", "320193");
        args.putArray("tags").add("Assets");

        var r = new GetCompanyFactsTool(svc).call(args);

        assertThat(r.available()).isTrue();
        assertThat(r.output().get("cik").asString()).isEqualTo("0000320193");
        assertThat(r.output().get("facts").get("Assets").get("datapoints")).hasSize(1);
    }

    @Test void requestedTagAbsentYieldsEmptySeries() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(any(), any())).thenReturn("0000320193");
        when(svc.companyFacts(any(), any())).thenReturn(facts());
        var args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.putArray("tags").add("Assets").add("NotFiledConcept");

        var r = new GetCompanyFactsTool(svc).call(args);

        assertThat(r.available()).isTrue();
        JsonNode missing = r.output().get("facts").get("NotFiledConcept");
        assertThat(missing.get("unit").isNull()).isTrue();
        assertThat(missing.get("datapoints")).isEmpty();
    }

    @Test void serviceExceptionUnavailable() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(any(), any())).thenReturn("0000320193");
        when(svc.companyFacts(any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR HTTP 500", null));
        var args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.putArray("tags").add("Assets");

        assertThat(new GetCompanyFactsTool(svc).call(args).available()).isFalse();
    }

    @Test void notFoundReturnsOkEmptyPerTag() {
        EdgarService svc = Mockito.mock(EdgarService.class);
        when(svc.resolveCik(any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no CIK for ZZZ", null));
        var args = mapper.createObjectNode();
        args.put("symbol", "ZZZ");
        args.putArray("tags").add("Assets").add("LiabilitiesCurrent");

        var r = new GetCompanyFactsTool(svc).call(args);

        assertThat(r.available()).isTrue();
        assertThat(r.output().get("taxonomy").asString()).isEqualTo("us-gaap");
        JsonNode facts = r.output().get("facts");
        // Every requested tag present with an empty, well-formed series — "ran fine, no data".
        assertThat(facts.propertyNames()).containsExactlyInAnyOrder("Assets", "LiabilitiesCurrent");
        assertThat(facts.get("Assets").get("unit").isNull()).isTrue();
        assertThat(facts.get("Assets").get("datapoints")).isEmpty();
        assertThat(facts.get("LiabilitiesCurrent").get("datapoints")).isEmpty();
    }

    @Test void missingSymbolAndCikUnavailable() {
        var args = mapper.createObjectNode();
        args.putArray("tags").add("Assets");
        assertThat(new GetCompanyFactsTool(Mockito.mock(EdgarService.class)).call(args).available()).isFalse();
    }

    @Test void missingTagsUnavailable() {
        var args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        assertThat(new GetCompanyFactsTool(Mockito.mock(EdgarService.class)).call(args).available()).isFalse();
    }

    @Test void blankTagsUnavailable() {
        var args = mapper.createObjectNode();
        args.put("symbol", "AAPL");
        args.putArray("tags").add("").add("   ");
        assertThat(new GetCompanyFactsTool(Mockito.mock(EdgarService.class)).call(args).available()).isFalse();
    }

    @Test void namespaceIsGeneral() {
        assertThat(new GetCompanyFactsTool(Mockito.mock(EdgarService.class)).namespace()).isEqualTo("general");
    }
}
