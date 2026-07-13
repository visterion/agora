package de.visterion.agora.data;

import de.visterion.agora.trading.saxo.SaxoDataAccess;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

@Component
public class SaxoInstrumentResolver implements InstrumentResolver {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(SaxoInstrumentResolver.class);
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
            log.debug("saxo resolution failed for {}, falling back to raw: {}", input, e.toString());
            failureCache.put(input, Boolean.TRUE);
            return Instrument.raw(input);
        }
    }

    /** Saxo ref/v1 resolution — filled in Tasks 3 (suffix) and 4 (ISIN). */
    private Instrument lookup(String input, Instrument.InputKind kind) {
        String bearer = access.bearer().orElseThrow();
        int dot = input.lastIndexOf('.');
        String ticker = input.substring(0, dot);
        String exchangeId = SUFFIX_TO_EXCHANGE.get(input.substring(dot + 1).toUpperCase(Locale.ROOT));

        tools.jackson.databind.JsonNode root = access.http().get()
                .uri(uri -> uri.path("/ref/v1/instruments")
                        .queryParam("Keywords", ticker).queryParam("AssetTypes", "Stock")
                        .queryParam("$top", 10).queryParam("ExchangeId", exchangeId).build())
                .header("Authorization", bearer)
                .retrieve().body(tools.jackson.databind.JsonNode.class);

        tools.jackson.databind.JsonNode data = root == null ? null : root.path("Data");
        if (data == null || !data.isArray() || data.isEmpty()) throw new IllegalStateException("no hit");

        for (tools.jackson.databind.JsonNode hit : data) {
            if (!exchangeId.equals(hit.path("ExchangeId").asString(""))) continue;
            long id = hit.path("Identifier").asLong(0);
            if (id == 0) continue;
            return new Instrument(input, input, null, null, exchangeId,
                    hit.path("CurrencyCode").asString(null), id, null, "Stock", true);
        }
        throw new IllegalStateException("no exchange hit");
    }
}
