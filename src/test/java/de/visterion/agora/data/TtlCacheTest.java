package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class TtlCacheTest {

    @Test
    void missLoadsThenHitDoesNot() {
        AtomicInteger loads = new AtomicInteger();
        TtlCache<String, String> cache = new TtlCache<>(1000, () -> 0L);
        assertThat(cache.get("k", () -> { loads.incrementAndGet(); return "v"; })).isEqualTo("v");
        assertThat(cache.get("k", () -> { loads.incrementAndGet(); return "v"; })).isEqualTo("v");
        assertThat(loads.get()).isEqualTo(1); // second call served from cache
    }

    @Test
    void expiryReloads() {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong(0L);
        TtlCache<String, String> cache = new TtlCache<>(1000, now::get);
        cache.get("k", () -> { loads.incrementAndGet(); return "v"; });
        now.set(1001); // past TTL
        cache.get("k", () -> { loads.incrementAndGet(); return "v"; });
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void loaderExceptionIsNotCached() {
        AtomicInteger loads = new AtomicInteger();
        TtlCache<String, String> cache = new TtlCache<>(1000, () -> 0L);
        assertThatThrownBy(() -> cache.get("k", () -> { loads.incrementAndGet(); throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class);
        // next call retries (nothing cached)
        assertThat(cache.get("k", () -> { loads.incrementAndGet(); return "v"; })).isEqualTo("v");
        assertThat(loads.get()).isEqualTo(2);
    }
}
