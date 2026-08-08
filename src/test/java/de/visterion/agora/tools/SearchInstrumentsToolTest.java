package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.search.SearchHit;
import de.visterion.agora.tool.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SearchInstrumentsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AtomicInteger seenLimit = new AtomicInteger(-1);

    private SearchInstrumentsTool toolReturning(List<SearchHit> hits) {
        return new SearchInstrumentsTool((q, limit) -> { seenLimit.set(limit); return hits; });
    }

    private static ObjectNode args(String query, Integer limit) {
        ObjectNode n = MAPPER.createObjectNode();
        if (query != null) n.put("query", query);
        if (limit != null) n.put("limit", limit);
        return n;
    }

    private static SearchHit hit(String symbol) {
        return new SearchHit(symbol, symbol + " Corp", "NYSE", "EQUITY");
    }

    @Test void mapsHitsToTheDocumentedShape() {
        ToolResult r = toolReturning(List.of(hit("SYNA"))).call(args("nokia", 10));

        assertThat(r.available()).isTrue();
        JsonNode row = r.output().path("results").get(0);
        assertThat(row.path("symbol").asString()).isEqualTo("SYNA");
        assertThat(row.path("name").asString()).isEqualTo("SYNA Corp");
        assertThat(row.path("exchange").asString()).isEqualTo("NYSE");
        assertThat(row.path("type").asString()).isEqualTo("EQUITY");
        assertThat(row.has("currency")).isFalse();
    }

    @Test void missingOrBlankQueryIsUnavailable() {
        assertThat(toolReturning(List.of()).call(args(null, 10)).available()).isFalse();
        assertThat(toolReturning(List.of()).call(args("   ", 10)).available()).isFalse();
        assertThat(toolReturning(List.of()).call(null).available()).isFalse();
    }

    @Test void limitDefaultsToTenAndClampsToTwentyFive() {
        toolReturning(List.of()).call(args("nokia", null));
        assertThat(seenLimit).hasValue(10);

        toolReturning(List.of()).call(args("nokia", 100));
        assertThat(seenLimit).hasValue(25);

        toolReturning(List.of()).call(args("nokia", 0));
        assertThat(seenLimit).hasValue(1);

        toolReturning(List.of()).call(args("nokia", -5));
        assertThat(seenLimit).hasValue(1);
    }

    @Test void limitAsNumericStringIsHonouredNotSilentlyDefaulted() {
        ObjectNode n = args("nokia", null);
        n.put("limit", "5");

        ToolResult r = toolReturning(List.of()).call(n);

        assertThat(r.available()).isTrue();
        assertThat(seenLimit).hasValue(5);
    }

    @Test void unparsableLimitIsUnavailableNotSilentlyDefaulted() {
        ObjectNode boolArgs = args("nokia", null);
        boolArgs.put("limit", true);
        ToolResult asBoolean = toolReturning(List.of()).call(boolArgs);
        assertThat(asBoolean.available()).isFalse();
        assertThat(asBoolean.error()).contains("invalid integer argument: limit");
        assertThat(seenLimit).hasValue(-1); // the service must never have been called

        ObjectNode objectArgs = args("nokia", null);
        objectArgs.putObject("limit");
        ToolResult asObject = toolReturning(List.of()).call(objectArgs);
        assertThat(asObject.available()).isFalse();
        assertThat(asObject.error()).contains("invalid integer argument: limit");
        assertThat(seenLimit).hasValue(-1);
    }

    @Test void zeroHitsIsAnAvailableEmptyResultNotAnError() {
        ToolResult r = toolReturning(List.of()).call(args("nothingmatchesthis", 10));

        assertThat(r.available()).isTrue();
        assertThat(r.output().path("results")).isEmpty();
    }

    @Test void upstreamFailureIsUnavailable() {
        SearchInstrumentsTool tool = new SearchInstrumentsTool((q, limit) -> {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "yahoo down", null);
        });

        ToolResult r = tool.call(args("nokia", 10));

        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("yahoo down");
    }

    @Test void toolIsNamedAndInTheGeneralNamespace() {
        SearchInstrumentsTool tool = toolReturning(List.of());

        assertThat(tool.name()).isEqualTo("search_instruments");
        assertThat(tool.namespace()).isEqualTo("general");
        assertThat(tool.inputSchema().path("properties").has("query")).isTrue();
        assertThat(tool.inputSchema().path("properties").has("limit")).isTrue();
    }
}
