package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class EarningsServiceTest {

    private EarningsProvider fixed(String name, List<EarningsEvent> events, boolean throwUnavailable) {
        return new EarningsProvider() {
            public String name() { return name; }
            public List<EarningsEvent> earnings(String s, LocalDate f, LocalDate t) {
                if (throwUnavailable) throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, name + " down", null);
                return events;
            }
        };
    }

    @Test void usesFirstHealthyProvider() {
        var ev = List.of(new EarningsEvent("AAPL", LocalDate.parse("2025-05-01"),
                new BigDecimal("1.4"), new BigDecimal("1.5"), new BigDecimal("7.1"), null, null));
        var svc = new EarningsService(List.of(fixed("finnhub", ev, false), fixed("yahoo", List.of(), false)),
                120L, System::currentTimeMillis);
        assertThat(svc.earnings("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"))).hasSize(1);
    }

    @Test void fallsBackWhenPrimaryUnavailable() {
        var ev = List.of(new EarningsEvent("AAPL", LocalDate.parse("2025-05-01"),
                null, null, null, null, null));
        var svc = new EarningsService(List.of(fixed("finnhub", List.of(), true), fixed("yahoo", ev, false)),
                120L, System::currentTimeMillis);
        assertThat(svc.earnings("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"))).hasSize(1);
    }

    @Test void cachesResultAcrossIdenticalCalls() {
        int[] count = {0};
        EarningsProvider counting = new EarningsProvider() {
            public String name() { return "counting"; }
            public List<EarningsEvent> earnings(String s, LocalDate f, LocalDate t) {
                count[0]++;
                return List.of(new EarningsEvent("AAPL", LocalDate.parse("2025-05-01"),
                        null, null, null, null, null));
            }
        };
        var svc = new EarningsService(List.of(counting), 120L, System::currentTimeMillis);
        var from = LocalDate.parse("2025-01-01");
        var to = LocalDate.parse("2025-12-31");
        svc.earnings("AAPL", from, to);
        svc.earnings("AAPL", from, to);
        assertThat(count[0]).isEqualTo(1);
    }

    @Test void earningsWindowUsesMarketWideAndCachesSeparately() {
        int[] count = {0};
        EarningsProvider counting = new EarningsProvider() {
            public String name() { return "counting"; }
            public List<EarningsEvent> earnings(String s, LocalDate f, LocalDate t) {
                count[0]++;
                assertThat(s).isNull(); // window mode passes null symbol
                return List.of(new EarningsEvent("AAPL", LocalDate.parse("2025-05-01"),
                        null, null, null, null, null));
            }
        };
        var svc = new EarningsService(List.of(counting), 120L, System::currentTimeMillis);
        var from = LocalDate.parse("2025-05-01");
        var to = LocalDate.parse("2025-05-03");
        assertThat(svc.earningsWindow(from, to)).hasSize(1);
        svc.earningsWindow(from, to);
        assertThat(count[0]).isEqualTo(1); // window result cached
    }

    @Test void allUnavailableThrows() {
        var svc = new EarningsService(List.of(fixed("finnhub", List.of(), true), fixed("yahoo", List.of(), true)),
                120L, System::currentTimeMillis);
        assertThatThrownBy(() -> svc.earnings("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void emptyListFromProviderFallsThroughToNextProvider() {
        var ev = List.of(new EarningsEvent("AAPL", LocalDate.parse("2025-05-01"),
                null, null, null, null, null));
        // finnhub answers successfully but with zero events — must be treated as "no answer",
        // not as the final result, so yahoo still gets a chance.
        var svc = new EarningsService(List.of(fixed("finnhub", List.of(), false), fixed("yahoo", ev, false)),
                120L, System::currentTimeMillis);
        assertThat(svc.earnings("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"))).hasSize(1);
    }

    @Test void allEmptyThrowsAndIsNotCached() {
        int[] count = {0};
        EarningsProvider countingEmpty = new EarningsProvider() {
            public String name() { return "counting-empty"; }
            public List<EarningsEvent> earnings(String s, LocalDate f, LocalDate t) {
                count[0]++;
                return List.of();
            }
        };
        var svc = new EarningsService(List.of(countingEmpty), 120L, System::currentTimeMillis);
        var from = LocalDate.parse("2025-01-01");
        var to = LocalDate.parse("2025-12-31");
        assertThatThrownBy(() -> svc.earnings("AAPL", from, to)).isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> svc.earnings("AAPL", from, to)).isInstanceOf(MarketDataException.class);
        // Nothing was cached on the all-empty outcome: the provider is hit again on the second call.
        assertThat(count[0]).isEqualTo(2);
    }
}
