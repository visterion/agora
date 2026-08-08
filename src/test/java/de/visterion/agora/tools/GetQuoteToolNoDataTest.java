package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.Quote;
import de.visterion.agora.data.QuoteBatch;
import de.visterion.agora.tool.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GetQuoteToolNoDataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode symbols(String... syms) {
        ObjectNode n = MAPPER.createObjectNode();
        var arr = n.putArray("symbols");
        for (String s : syms) arr.add(s);
        return n;
    }

    private static Quote quote(String symbol) {
        return new Quote(symbol, new BigDecimal("10.0"), new BigDecimal("0.5"), "USD");
    }

    private static GetQuoteTool toolFor(QuoteBatch batch) {
        MarketDataService svc = Mockito.mock(MarketDataService.class);
        Mockito.when(svc.quotes(Mockito.anyCollection())).thenReturn(batch);
        return new GetQuoteTool(svc);
    }

    @Test void unknownSingleSymbolIsNoDataNotAnOutage() {
        ToolResult r = toolFor(new QuoteBatch(
                Map.of(), Map.of("NOKIA", MarketDataException.Kind.NOT_FOUND)))
                .call(symbols("NOKIA"));

        assertThat(r.available()).isTrue();                                  // -> isError:false
        assertThat(r.output().path("available").asBoolean(true)).isFalse();
        assertThat(r.output().path("quotes")).isEmpty();
        assertThat(r.output().path("unresolved").get(0).asString()).isEqualTo("NOKIA");
    }

    @Test void anIntermediateProviderFailureStillYieldsNoDataWhenTheKindIsNotFound() {
        // The kind that reaches the tool is the LAST provider's; an UNAVAILABLE thrown by an
        // earlier provider (e.g. Saxo without a uic) must not turn this into an outage.
        ToolResult r = toolFor(new QuoteBatch(
                Map.of(), Map.of("NOKIA", MarketDataException.Kind.NOT_FOUND)))
                .call(symbols("NOKIA"));

        assertThat(r.available()).isTrue();
    }

    @Test void mixedBatchWithOneUnavailableSymbolStaysASuccess() {
        ToolResult r = toolFor(new QuoteBatch(
                Map.of("NOK", quote("NOK")),
                Map.of("SIE.DE", MarketDataException.Kind.UNAVAILABLE)))
                .call(symbols("NOK", "SIE.DE"));

        assertThat(r.available()).isTrue();
        assertThat(r.output().path("quotes")).hasSize(1);
        assertThat(r.output().path("unresolved").get(0).asString()).isEqualTo("SIE.DE");
    }

    @Test void nothingResolvedAndAtLeastOneRealOutageIsUnavailable() {
        ToolResult r = toolFor(new QuoteBatch(
                Map.of(), Map.of("NOKIA", MarketDataException.Kind.NOT_FOUND,
                                 "SIE.DE", MarketDataException.Kind.UNAVAILABLE)))
                .call(symbols("NOKIA", "SIE.DE"));

        assertThat(r.available()).isFalse();
    }

    @Test void partialSuccessPayloadIsUnchanged() {
        ToolResult r = toolFor(new QuoteBatch(
                Map.of("NOK", quote("NOK")),
                Map.of("NOKIA", MarketDataException.Kind.NOT_FOUND)))
                .call(symbols("NOK", "NOKIA"));

        assertThat(r.available()).isTrue();
        assertThat(r.output().path("quotes").get(0).path("symbol").asString()).isEqualTo("NOK");
        assertThat(r.output().path("unresolved").get(0).asString()).isEqualTo("NOKIA");
        assertThat(r.output().has("available")).isFalse();   // plain ok(), no noData marker
    }

    @Test void lowercaseSymbolKeepsItsFailureKind() {
        // The regression guard for key normalisation: reasons are keyed by the RAW request
        // symbol. Keying by the uppercased cache key would make the reason set empty and turn
        // an outage into "symbol not found".
        ToolResult notFound = toolFor(new QuoteBatch(
                Map.of(), Map.of("nokia", MarketDataException.Kind.NOT_FOUND)))
                .call(symbols("nokia"));
        assertThat(notFound.available()).isTrue();

        ToolResult outage = toolFor(new QuoteBatch(
                Map.of(), Map.of("nokia", MarketDataException.Kind.UNAVAILABLE)))
                .call(symbols("nokia"));
        assertThat(outage.available()).isFalse();
    }

    @Test void aSymbolWithNoRecordedReasonCountsAsAnOutageNotAsNotFound() {
        ToolResult r = toolFor(new QuoteBatch(Map.of(), Map.of())).call(symbols("MYSTERY"));

        assertThat(r.available()).isFalse();
    }

    @Test void emptySymbolListKeepsTheOldEarlyReturn() {
        ToolResult r = toolFor(new QuoteBatch(Map.of(), Map.of()))
                .call(MAPPER.createObjectNode());

        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("no symbols provided");
    }

    @Test void duplicateSymbolsArePreservedInUnresolved() {
        ToolResult r = toolFor(new QuoteBatch(
                Map.of(), Map.of("NOKIA", MarketDataException.Kind.NOT_FOUND)))
                .call(symbols("NOKIA", "NOKIA"));

        assertThat(r.output().path("unresolved")).hasSize(2);
    }
}
