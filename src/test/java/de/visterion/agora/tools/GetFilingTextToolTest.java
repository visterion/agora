package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarSearchService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GetFilingTextToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsExtractedText() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.filingText(any())).thenReturn(new EdgarSearchService.FilingText(
                "SUMMARY TERM SHEET the offer is $52.00 cash", true, false, 43,
                "https://www.sec.gov/Archives/edgar/data/1/x.htm"));
        var args = mapper.createObjectNode();
        args.put("url", "https://www.sec.gov/Archives/edgar/data/1/x.htm");

        var r = new GetFilingTextTool(svc).call(args);

        assertThat(r.available()).isTrue();
        assertThat(r.output().get("text").asString()).contains("$52.00");
        assertThat(r.output().get("section_found").asBoolean()).isTrue();
        assertThat(r.output().get("truncated").asBoolean()).isFalse();
        assertThat(r.output().get("char_count").asInt()).isEqualTo(43);
        assertThat(r.output().get("source_url").asString()).endsWith("/x.htm");
    }

    @Test void blankUrlIsUnavailable() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        var r = new GetFilingTextTool(svc).call(mapper.createObjectNode());
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("url");
    }

    @Test void serviceFailureIsUnavailable() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.filingText(any())).thenThrow(
                new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "boom", null));
        var args = mapper.createObjectNode();
        args.put("url", "https://www.sec.gov/Archives/edgar/data/1/x.htm");
        var r = new GetFilingTextTool(svc).call(args);
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("boom");
    }

    /**
     * A4: the too-large token must survive to the wire. ToolResult carries no kind, so this
     * string is the ONLY thing a consumer (Dracul) can key on to tell "this one document is
     * permanently oversized" from "Agora/EDGAR is down" — which it previously could not.
     */
    @Test void oversizedFilingSurfacesTheTooLargeTokenOnTheWire() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.filingText(any())).thenThrow(new MarketDataException(
                MarketDataException.Kind.TOO_LARGE,
                "filing_too_large: document is more than 33554432 bytes (no usable Content-Length), "
                        + "cap is 33554432 bytes (raise agora.data.edgar.max-filing-bytes): "
                        + "https://www.sec.gov/Archives/edgar/data/1/x.htm", null));
        var args = mapper.createObjectNode();
        args.put("url", "https://www.sec.gov/Archives/edgar/data/1/x.htm");
        var r = new GetFilingTextTool(svc).call(args);
        assertThat(r.available()).isFalse();
        assertThat(r.error()).startsWith("filing_too_large:");
        assertThat(r.error()).contains("agora.data.edgar.max-filing-bytes");
    }
}
