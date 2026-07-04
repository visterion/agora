package de.visterion.agora.data;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.mockito.Mockito.*;

class FxWarmerTest {

    @Test void warmsEachConfiguredPair() {
        FxService fx = Mockito.mock(FxService.class);
        when(fx.rate(anyString(), anyString())).thenReturn(new FxRate("EUR", "USD", BigDecimal.ONE));
        FxWarmer warmer = new FxWarmer(fx, "EURUSD,GBPUSD");
        warmer.refresh();
        verify(fx).rate("EUR", "USD");
        verify(fx).rate("GBP", "USD");
    }

    @Test void emptyListWarmsNothing() {
        FxService fx = Mockito.mock(FxService.class);
        new FxWarmer(fx, "").refresh();
        verifyNoInteractions(fx);
    }

    @Test void neverThrowsOnServiceFailure() {
        FxService fx = Mockito.mock(FxService.class);
        when(fx.rate(anyString(), anyString()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null));
        FxWarmer warmer = new FxWarmer(fx, "EURUSD");
        warmer.refresh(); // must not throw
    }
}
