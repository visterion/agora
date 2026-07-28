package de.visterion.agora.fetch.earnings;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderCooldownTest {

    private final AtomicLong clock = new AtomicLong(0L);

    private EarningsProvider named(String n) {
        return new EarningsProvider() {
            public String name() { return n; }
            public List<EarningsEvent> earnings(String s, LocalDate f, LocalDate t) { return List.of(); }
        };
    }

    private ProviderCooldown cooldown() {
        return new ProviderCooldown(3, 600_000L, clock::get);
    }

    @Test void staysHotBelowTheThreshold() {
        var c = cooldown();
        var p = named("yahoo");
        c.recordFailure(p);
        c.recordFailure(p);
        assertThat(c.isCooled(p)).isFalse();
    }

    @Test void coolsAtTheThresholdAndRecoversAfterTheWindow() {
        var c = cooldown();
        var p = named("yahoo");
        for (int i = 0; i < 3; i++) c.recordFailure(p);
        assertThat(c.isCooled(p)).isTrue();

        clock.set(599_999L);
        assertThat(c.isCooled(p)).isTrue();

        clock.set(600_001L);
        assertThat(c.isCooled(p)).isFalse();
    }

    @Test void successResetsTheCounter() {
        var c = cooldown();
        var p = named("yahoo");
        c.recordFailure(p);
        c.recordFailure(p);
        c.recordSuccess(p);
        c.recordFailure(p);
        assertThat(c.isCooled(p)).isFalse();
    }

    @Test void countersAreIsolatedPerProviderInstanceEvenWhenNamesCollide() {
        // FinnhubEarningsProvider and FinnhubMarketDataProvider both report name() = "finnhub".
        var c = cooldown();
        var earnings = named("finnhub");
        var quotes = named("finnhub");

        for (int i = 0; i < 3; i++) c.recordFailure(earnings);

        assertThat(c.isCooled(earnings)).isTrue();
        assertThat(c.isCooled(quotes)).isFalse();
    }
}
