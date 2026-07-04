package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.reference.Constituent;
import de.visterion.agora.fetch.reference.WikipediaService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GetIndexConstituentsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsConstituentsDefaultSp500() {
        WikipediaService svc = Mockito.mock(WikipediaService.class);
        when(svc.constituents(any())).thenReturn(List.of(
                new Constituent("AAPL", "Apple Inc.", "Information Technology", LocalDate.parse("1982-11-30"))));
        var r = new GetIndexConstituentsTool(svc).call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("index").asString()).isEqualTo("sp500");
        assertThat(r.output().get("constituents").get(0).get("symbol").asString()).isEqualTo("AAPL");
    }

    @Test void unknownIndexUnavailable() {
        WikipediaService svc = Mockito.mock(WikipediaService.class);
        when(svc.constituents(any())).thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "unknown index", null));
        assertThat(new GetIndexConstituentsTool(svc).call(mapper.createObjectNode().put("index", "nasdaq100")).available()).isFalse();
    }
}
