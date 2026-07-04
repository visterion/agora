package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderFallbackTest {

    /** Keyless TwelveData + Finnhub self-skip (UNAVAILABLE); a healthy stub last in order serves. */
    @Test void keylessProvidersAreSkipped_healthyProviderServes() {
        MarketDataProvider healthy = new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) { return new Quote(s, new BigDecimal("5.55"), BigDecimal.ZERO, "USD"); }
            public List<OhlcBar> ohlc(String s, int d) { return List.of(); }
        };
        // order: keyless twelvedata, keyless finnhub, then healthy stub
        var svc = new MarketDataService(
                List.of(new TwelveDataMarketDataProvider("http://unused", ""),
                        new FinnhubMarketDataProvider("http://unused", ""),
                        healthy),
                120L);
        assertThat(svc.quote("AAPL").price()).isEqualByComparingTo("5.55");
        assertThat(svc.ohlc("AAPL", 30)).isEmpty();
    }
}
