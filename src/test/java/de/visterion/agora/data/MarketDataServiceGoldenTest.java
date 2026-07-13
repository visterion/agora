package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MarketDataServiceGoldenTest {

    @Test void passThroughResolverKeepsStringBehaviour() {
        var seen = new java.util.ArrayList<String>();
        MarketDataProvider fake = new MarketDataProvider() {
            public String name() { return "fake"; }
            public Quote quote(String s) { seen.add(s); return new Quote(s,
                    java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO, "USD"); }
            public java.util.List<OhlcBar> ohlc(String s, int d) { return java.util.List.of(); }
        };
        MarketDataService svc = new MarketDataService(List.of(fake), 120_000L, () -> 0L);
        svc.quote("aapl");
        assertThat(seen).containsExactly("aapl");   // original casing to provider, default→displaySymbol
    }
}
