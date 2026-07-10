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

    // -- M-C1: bounded, self-evicting --

    @Test
    void sizeStaysBoundedUnderManyDistinctKeys() {
        AtomicLong now = new AtomicLong(0L);
        TtlCache<String, String> cache = new TtlCache<>(1_000_000, 100, now::get);
        for (int i = 0; i < 10_000; i++) {
            cache.get("k" + i, () -> "v");
        }
        // racy-but-safe eviction: allow a small slack over the cap, but must stay bounded
        assertThat(cache.size()).isLessThanOrEqualTo(100 + 16);
    }

    @Test
    void expiredEntriesAreRemovedUnderPressure() {
        AtomicLong now = new AtomicLong(0L);
        TtlCache<String, String> cache = new TtlCache<>(1000, 10, now::get);
        // fill with entries that will all be expired
        for (int i = 0; i < 10; i++) {
            cache.get("old" + i, () -> "v");
        }
        now.set(2000); // past TTL for all of the above
        // trigger the opportunistic sweep by writing more than the cap while everything is expired
        for (int i = 0; i < 10; i++) {
            cache.get("new" + i, () -> "v");
        }
        // the sweep should have purged the expired "old*" entries rather than growing unbounded
        assertThat(cache.size()).isLessThanOrEqualTo(10 + 4);
        for (int i = 0; i < 10; i++) {
            assertThat(cache.containsKey("old" + i)).isFalse();
        }
    }

    @Test
    void hotUnexpiredEntrySurvivesEvictionOfExpiredOnes() {
        AtomicLong now = new AtomicLong(0L);
        TtlCache<String, String> cache = new TtlCache<>(1000, 5, now::get);
        // 4 entries written at t=0 → expire at t=1000
        for (int i = 0; i < 4; i++) {
            cache.get("k" + i, () -> "v");
        }
        // "hot" written later at t=900 → expires at t=1900, i.e. still fresh once k0..k3 expire
        now.set(900);
        cache.get("hot", () -> "v");
        // map is now at the cap (5). Advance past k0..k3's expiry (but not hot's) and write one
        // more entry: this must trigger the cap check, whose opportunistic sweep purges the
        // expired k0..k3 (freeing room) without touching the still-fresh "hot" entry.
        now.set(1000);
        cache.get("k4", () -> "v");

        assertThat(cache.containsKey("hot")).isTrue();
        for (int i = 0; i < 4; i++) {
            assertThat(cache.containsKey("k" + i)).isFalse();
        }
    }

    @Test
    void defaultMaxSizeIsTenThousand() {
        AtomicLong now = new AtomicLong(0L);
        TtlCache<String, String> cache = new TtlCache<>(1000, now::get);
        for (int i = 0; i < 10_050; i++) {
            cache.get("k" + i, () -> "v");
        }
        assertThat(cache.size()).isLessThanOrEqualTo(10_000 + 64);
    }
}
