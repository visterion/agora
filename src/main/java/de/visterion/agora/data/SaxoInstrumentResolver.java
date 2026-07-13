package de.visterion.agora.data;

import de.visterion.agora.trading.saxo.SaxoDataAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

@Component
public class SaxoInstrumentResolver implements InstrumentResolver {

    static final Map<String, String> SUFFIX_TO_EXCHANGE = Map.of(
            "DE", "FSE", "MI", "MIL", "TO", "TSE");     // Saxo ExchangeId (≠ MIC). Slice-1 verified set.
    static final Set<String> SUFFIXES = SUFFIX_TO_EXCHANGE.keySet();
    private static final long TTL_MILLIS = 24 * 3600 * 1000L;
    private static final long NEGATIVE_TTL_MILLIS = 60 * 1000L;

    private final SaxoDataAccess access;
    private final TtlCache<String, Instrument> cache;
    private final TtlCache<String, Boolean> failureCache;

    @Autowired
    public SaxoInstrumentResolver(SaxoDataAccess access) { this(access, System::currentTimeMillis); }

    SaxoInstrumentResolver(SaxoDataAccess access, LongSupplier nowMillis) {
        this.access = access;
        this.cache = new TtlCache<>(TTL_MILLIS, 4096, nowMillis);
        this.failureCache = new TtlCache<>(NEGATIVE_TTL_MILLIS, 4096, nowMillis);
    }

    @Override
    public Instrument resolve(String input) {
        if (input == null || input.isBlank()) return Instrument.raw(input);
        Instrument.InputKind kind = Instrument.classify(input, SUFFIXES);
        if (kind == Instrument.InputKind.US_OR_UNMAPPED) return Instrument.raw(input);   // C3: no HTTP
        if (access.bearer().isEmpty()) return Instrument.raw(input);                     // no session: no HTTP
        if (failureCache.isFresh(input)) return Instrument.raw(input);
        try {
            return cache.get(input, () -> lookup(input, kind));
        } catch (RuntimeException e) {                    // resolver never throws into the chain
            failureCache.put(input, Boolean.TRUE);
            return Instrument.raw(input);
        }
    }

    /** Saxo ref/v1 resolution — filled in Tasks 3 (suffix) and 4 (ISIN). */
    private Instrument lookup(String input, Instrument.InputKind kind) {
        return Instrument.raw(input);   // placeholder; replaced next task
    }
}
