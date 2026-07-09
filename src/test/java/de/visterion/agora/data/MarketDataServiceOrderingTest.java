package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/**
 * Broker-first ordering: when the primary (Alpaca) serves, later providers are never consulted;
 * when the primary is UNAVAILABLE, the chain falls through to the next providers in order.
 */
class MarketDataServiceOrderingTest {

    /** A provider that records whether it was called and can be told to serve or self-skip. */
    private static final class TrackingProvider implements MarketDataProvider {
        final String name;
        final boolean available;
        final AtomicBoolean called = new AtomicBoolean(false);
        TrackingProvider(String name, boolean available) { this.name = name; this.available = available; }
        public String name() { return name; }
        public Quote quote(String symbol) {
            called.set(true);
            if (!available) throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, name + " down", null);
            return new Quote(symbol, new BigDecimal("1.23"), BigDecimal.ZERO, "USD");
        }
        public List<OhlcBar> ohlc(String symbol, int days) {
            called.set(true);
            if (!available) throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, name + " down", null);
            return List.of();
        }
    }

    @Test void primarySuccessMeansLaterProvidersNotCalled() {
        var alpaca = new TrackingProvider("alpaca", true);
        var saxo = new TrackingProvider("saxo", true);
        var twelve = new TrackingProvider("twelvedata", true);
        var finnhub = new TrackingProvider("finnhub", true);
        var yahoo = new TrackingProvider("yahoo", true);
        var svc = new MarketDataService(List.of(alpaca, saxo, twelve, finnhub, yahoo), 1000, () -> 0L);

        Quote q = svc.quote("AAPL");

        assertThat(q.price()).isEqualByComparingTo("1.23");
        assertThat(alpaca.called).isTrue();
        assertThat(saxo.called).isFalse();
        assertThat(twelve.called).isFalse();
        assertThat(finnhub.called).isFalse();
        assertThat(yahoo.called).isFalse();
    }

    @Test void primaryUnavailableFallsThroughInOrder() {
        var alpaca = new TrackingProvider("alpaca", false);
        var saxo = new TrackingProvider("saxo", false);
        var twelve = new TrackingProvider("twelvedata", false);
        var finnhub = new TrackingProvider("finnhub", false);
        var yahoo = new TrackingProvider("yahoo", true);
        var svc = new MarketDataService(List.of(alpaca, saxo, twelve, finnhub, yahoo), 1000, () -> 0L);

        Quote q = svc.quote("AAPL");

        assertThat(q.price()).isEqualByComparingTo("1.23");
        assertThat(alpaca.called).isTrue();
        assertThat(saxo.called).isTrue();
        assertThat(twelve.called).isTrue();
        assertThat(finnhub.called).isTrue();
        assertThat(yahoo.called).isTrue();
    }

    @Test void alpacaDownSaxoServesAndLaterProvidersNotCalled() {
        var alpaca = new TrackingProvider("alpaca", false);
        var saxo = new TrackingProvider("saxo", true);
        var twelve = new TrackingProvider("twelvedata", true);
        var svc = new MarketDataService(List.of(alpaca, saxo, twelve), 1000, () -> 0L);

        Quote q = svc.quote("SAP.DE");

        assertThat(q.price()).isEqualByComparingTo("1.23");
        assertThat(alpaca.called).isTrue();
        assertThat(saxo.called).isTrue();
        assertThat(twelve.called).isFalse();
    }
}
