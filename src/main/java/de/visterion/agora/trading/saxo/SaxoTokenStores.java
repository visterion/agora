package de.visterion.agora.trading.saxo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** One SaxoTokenStore per connection id, shared by auth endpoints, refresher, and provider. */
@Component
public class SaxoTokenStores {

    private final Path dir;
    private final LongSupplier nowMillis;
    private final ConcurrentHashMap<String, SaxoTokenStore> stores = new ConcurrentHashMap<>();

    @Autowired
    public SaxoTokenStores(@Value("${agora.trading.saxo.token-dir:/data/saxo}") String dir) {
        this(Path.of(dir), System::currentTimeMillis);
    }

    SaxoTokenStores(Path dir, LongSupplier nowMillis) {
        this.dir = dir;
        this.nowMillis = nowMillis;
    }

    public SaxoTokenStore forConnection(String connectionId) {
        return stores.computeIfAbsent(connectionId, id -> new SaxoTokenStore(id, dir, nowMillis));
    }
}
