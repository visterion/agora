package de.visterion.agora.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Thread-safe, bounded, self-evicting TTL cache. Stores only successful loads — a throwing
 * loader is never cached. Time source is injectable for deterministic tests.
 *
 * <p>Under concurrent cache misses for the same key the loader may run more than once;
 * the last writer wins. This is benign for idempotent reads (e.g. market-data fetches)
 * but means the loader is <em>not</em> exactly-once guaranteed.
 *
 * <p>Size is capped at {@code maxSize}. On a miss write, once the map has reached the cap
 * an opportunistic sweep first removes expired entries; if the map is still at/over the
 * cap after the sweep, the single entry with the earliest expiry is evicted. Eviction is
 * racy-but-safe under concurrent writers — the map may transiently exceed {@code maxSize}
 * by a small margin, but never grows unbounded.
 */
public class TtlCache<K, V> {

    private record Entry<V>(V value, long expiresAtMillis) {}

    private final long ttlMillis;
    private final long maxSize;
    private final LongSupplier nowMillis;
    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();

    /** Keeps the pre-M-C1 signature working; defaults maxSize to 10_000. */
    public TtlCache(long ttlMillis, LongSupplier nowMillis) {
        this(ttlMillis, 10_000, nowMillis);
    }

    public TtlCache(long ttlMillis, long maxSize, LongSupplier nowMillis) {
        this.ttlMillis = ttlMillis;
        this.maxSize = maxSize;
        this.nowMillis = nowMillis;
    }

    public V get(K key, Supplier<V> loader) {
        long now = nowMillis.getAsLong();
        Entry<V> e = map.get(key);
        if (e != null && e.expiresAtMillis() > now) {
            return e.value();
        }
        V value = loader.get();              // throws → nothing stored
        if (map.size() >= maxSize) {
            evictToMakeRoom(now);
        }
        map.put(key, new Entry<>(value, now + ttlMillis));
        return value;
    }

    /** Number of entries currently held (including possibly-expired ones not yet swept). */
    public int size() {
        return map.size();
    }

    public boolean containsKey(K key) {
        return map.containsKey(key);
    }

    /** True if {@code key} holds a value that has not yet expired (unlike {@link #containsKey},
     *  which does not check the TTL). Use this for read-before-load short-circuits. */
    public boolean isFresh(K key) {
        Entry<V> e = map.get(key);
        return e != null && e.expiresAtMillis() > nowMillis.getAsLong();
    }

    private void evictToMakeRoom(long now) {
        map.values().removeIf(e -> e.expiresAtMillis() <= now);
        if (map.size() >= maxSize) {
            evictEarliestExpiring();
        }
    }

    private void evictEarliestExpiring() {
        K oldestKey = null;
        long oldestExpiry = Long.MAX_VALUE;
        for (Map.Entry<K, Entry<V>> e : map.entrySet()) {
            long expiry = e.getValue().expiresAtMillis();
            if (expiry < oldestExpiry) {
                oldestExpiry = expiry;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            map.remove(oldestKey);
        }
    }
}
