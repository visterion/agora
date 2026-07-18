package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/**
 * Exchange-aware routing at the {@code MarketDataService.firstSuccess} level: providers that
 * decline an instrument via {@code canServe} are skipped entirely (never invoked), and a skip
 * must not corrupt the all-NOT_FOUND negative-cache tracking (P1 regression, m8).
 */
class MarketDataServiceRoutingTest {

    /** A provider whose canServe/quote/ohlc behaviour is fully controlled by the test. */
    private static final class FakeProvider implements MarketDataProvider {
        final String name;
        final boolean canServe;
        final MarketDataException.Kind failWith; // null = success
        final AtomicBoolean called = new AtomicBoolean(false);

        FakeProvider(String name, boolean canServe, MarketDataException.Kind failWith) {
            this.name = name; this.canServe = canServe; this.failWith = failWith;
        }

        public String name() { return name; }

        @Override public boolean canServe(Instrument inst) { return canServe; }

        public Quote quote(String symbol) {
            called.set(true);
            if (failWith != null) throw new MarketDataException(failWith, name + " " + failWith, null);
            return new Quote(symbol, new BigDecimal("9.99"), BigDecimal.ZERO, "USD");
        }

        public List<OhlcBar> ohlc(String symbol, int days) {
            called.set(true);
            if (failWith != null) throw new MarketDataException(failWith, name + " " + failWith, null);
            return List.of();
        }
    }

    @Test void nonUsInstrumentSkipsUsOnlyProviders_saxoServes() {
        var alpaca = new FakeProvider("alpaca", false, null);
        var saxo = new FakeProvider("saxo", true, null);
        var twelve = new FakeProvider("twelvedata", false, null);
        var finnhub = new FakeProvider("finnhub", false, null);
        var yahoo = new FakeProvider("yahoo", true, null);
        var svc = new MarketDataService(List.of(alpaca, saxo, twelve, finnhub, yahoo), 1000, () -> 0L);

        Quote q = svc.quote("ALV.DE");

        assertThat(q.price()).isEqualByComparingTo("9.99");
        assertThat(alpaca.called).as("alpaca never invoked").isFalse();
        assertThat(twelve.called).as("twelvedata never invoked").isFalse();
        assertThat(finnhub.called).as("finnhub never invoked").isFalse();
        assertThat(saxo.called).as("saxo invoked").isTrue();
        assertThat(yahoo.called).as("yahoo not needed, saxo already served").isFalse();
    }

    @Test void usInstrumentTriesAlpacaFirst() {
        var alpaca = new FakeProvider("alpaca", true, null);
        var saxo = new FakeProvider("saxo", true, null);
        var svc = new MarketDataService(List.of(alpaca, saxo), 1000, () -> 0L);

        Quote q = svc.quote("AAPL");

        assertThat(q.price()).isEqualByComparingTo("9.99");
        assertThat(alpaca.called).isTrue();
        assertThat(saxo.called).isFalse();
    }

    /** P1 regression (m8): a skip must not touch allNotFound — when the remaining (non-skipped)
     *  providers all answer NOT_FOUND, the negative cache must still be populated. */
    @Test void skipDoesNotBreakAllNotFoundNegativeCache() {
        var alpaca = new FakeProvider("alpaca", false, null);
        var saxo = new FakeProvider("saxo", true, MarketDataException.Kind.NOT_FOUND);
        var twelve = new FakeProvider("twelvedata", false, null);
        var finnhub = new FakeProvider("finnhub", false, null);
        var yahoo = new FakeProvider("yahoo", true, MarketDataException.Kind.NOT_FOUND);
        var svc = new MarketDataService(List.of(alpaca, saxo, twelve, finnhub, yahoo), 1000, () -> 0L);

        assertThatThrownBy(() -> svc.quote("ALV.DE"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));

        // Second call for the same symbol must hit the negative cache (providers not re-invoked).
        saxo.called.set(false);
        yahoo.called.set(false);
        assertThatThrownBy(() -> svc.quote("ALV.DE"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("cached NOT_FOUND");
        assertThat(saxo.called).as("saxo not re-invoked, served from negative cache").isFalse();
        assertThat(yahoo.called).as("yahoo not re-invoked, served from negative cache").isFalse();
    }
}
