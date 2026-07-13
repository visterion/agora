package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;
import de.visterion.agora.data.InstrumentResolver;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.finnhub.Fundamentals;
import de.visterion.agora.fetch.finnhub.FundamentalsService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GlobalMetricsRouterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FundamentalsService finnhub = mock(FundamentalsService.class);
    private final GlobalMetricsService global = mock(GlobalMetricsService.class);
    private final InstrumentResolver resolver = mock(InstrumentResolver.class);
    private final Set<String> suffixes = Set.of("DE", "L", "T", "HK", "PA", "AS", "SW", "AX", "MI", "TO", "ST", "CO", "OL");

    @Test void flagOnNonUsGoesGlobal() {
        String symbol = "SAP.DE";
        Instrument inst = Instrument.raw(symbol);
        when(resolver.resolve(symbol)).thenReturn(inst);
        Fundamentals marker = new Fundamentals(symbol, mapper.createObjectNode().put("peTTM", 1.0));
        when(global.metrics(inst)).thenReturn(marker);

        GlobalMetricsRouter router = new GlobalMetricsRouter(finnhub, global, resolver, true, suffixes);
        Fundamentals result = router.fundamentals(symbol);

        assertThat(result).isSameAs(marker);
        verify(global).metrics(inst);
        verifyNoInteractions(finnhub);
    }

    @Test void flagOnUsGoesFinnhub() {
        String symbol = "AAPL";
        Instrument inst = Instrument.raw(symbol);
        when(resolver.resolve(symbol)).thenReturn(inst);
        Fundamentals marker = new Fundamentals(symbol, mapper.createObjectNode().put("peTTM", 2.0));
        when(finnhub.fundamentals(symbol)).thenReturn(marker);

        GlobalMetricsRouter router = new GlobalMetricsRouter(finnhub, global, resolver, true, suffixes);
        Fundamentals result = router.fundamentals(symbol);

        assertThat(result).isSameAs(marker);
        verify(finnhub).fundamentals(symbol);
        verifyNoInteractions(global);
    }

    @Test void flagOffNonUsGoesFinnhub() {
        String symbol = "SAP.DE";
        Instrument inst = Instrument.raw(symbol);
        when(resolver.resolve(symbol)).thenReturn(inst);
        when(finnhub.fundamentals(symbol)).thenThrow(
                new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "unsupported", null));

        GlobalMetricsRouter router = new GlobalMetricsRouter(finnhub, global, resolver, false, suffixes);

        assertThatThrownBy(() -> router.fundamentals(symbol)).isInstanceOf(MarketDataException.class);
        verify(finnhub).fundamentals(symbol);
        verifyNoInteractions(global);
    }
}
