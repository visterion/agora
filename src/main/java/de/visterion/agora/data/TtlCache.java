package de.visterion.agora.data;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Thread-safe TTL cache. Stores only successful loads — a throwing loader is never cached.
 * Time source is injectable for deterministic tests.
 *
 * <p>Under concurrent cache misses for the same key the loader may run more than once;
 * the last writer wins. This is benign for idempotent reads (e.g. market-data fetches)
 * but means the loader is <em>not</em> exactly-once guaranteed.
 */
public class TtlCache<K, V> {

    private record Entry<V>(V value, long expiresAtMillis) {}

    private final long ttlMillis;
    private final LongSupplier nowMillis;
    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();

    public TtlCache(long ttlMillis, LongSupplier nowMillis) {
        this.ttlMillis = ttlMillis;
        this.nowMillis = nowMillis;
    }

    public V get(K key, Supplier<V> loader) {
        long now = nowMillis.getAsLong();
        Entry<V> e = map.get(key);
        if (e != null && e.expiresAtMillis() > now) {
            return e.value();
        }
        V value = loader.get();              // throws → nothing stored
        map.put(key, new Entry<>(value, now + ttlMillis));
        return value;
    }
}
