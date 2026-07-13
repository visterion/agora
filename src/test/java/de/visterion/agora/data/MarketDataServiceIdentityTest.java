package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MarketDataServiceIdentityTest {

    @Test void resolvedInstrumentIsPassedToProviders_keyUnchanged() {
        var seen = new java.util.ArrayList<Instrument>();
        MarketDataProvider fake = new MarketDataProvider() {
            public String name() { return "fake"; }
            public Quote quote(String s) { throw new AssertionError("string path used"); }
            public java.util.List<OhlcBar> ohlc(String s, int d) { return java.util.List.of(); }
            public Quote quote(Instrument i) { seen.add(i); return new Quote(i.rawInput(),
                    java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO, "EUR"); }
        };
        InstrumentResolver stub = s -> new Instrument(s, s, null, null, "FSE", "EUR", 1126L, null, "Stock", true, 1.0);
        MarketDataService svc = new MarketDataService(List.of(fake), 120_000L, () -> 0L, stub);
        Quote q = svc.quote("SAP.DE");
        assertThat(q.symbol()).isEqualTo("SAP.DE");
        assertThat(seen).singleElement().extracting(Instrument::uic).isEqualTo(1126L);
    }
}
