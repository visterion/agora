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
    static final Map<String, String> SUFFIX_TO_EXCHANGE = Map.ofEntries(
            Map.entry("DE", "FSE"),   Map.entry("MI", "MIL"),  Map.entry("TO", "TSE"),
            Map.entry("L",  "LSE_SETS"), Map.entry("T", "TYO"), Map.entry("HK", "HKEX"),
            Map.entry("PA", "PAR"),   Map.entry("AS", "AMS"),  Map.entry("SW", "SWX"),
            Map.entry("AX", "ASX"));   // Saxo ExchangeId (≠ MIC), verified 2026-07-13 vs ref/v1/exchanges
    static final Set<String> SUFFIXES = SUFFIX_TO_EXCHANGE.keySet();
    private static final long TTL_MILLIS = 24 * 3600 * 1000L;
    private static final long NEGATIVE_TTL_MILLIS = 60 * 1000L;

    private final SaxoDataAccess access;
    private final TtlCache<String, Instrument> cache;
    private final TtlCache<String, Boolean> failureCache;
    private final TtlCache<Long, tools.jackson.databind.JsonNode> detailsCache;

    @Autowired
    public SaxoInstrumentResolver(SaxoDataAccess access) { this(access, System::currentTimeMillis); }

    SaxoInstrumentResolver(SaxoDataAccess access, LongSupplier nowMillis) {
        this.access = access;
        this.cache = new TtlCache<>(TTL_MILLIS, 4096, nowMillis);
        this.failureCache = new TtlCache<>(NEGATIVE_TTL_MILLIS, 4096, nowMillis);
        this.detailsCache = new TtlCache<>(TTL_MILLIS, 4096, nowMillis);
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
        if (kind == Instrument.InputKind.ISIN) return lookupIsin(input, bearer());
        String bearer = bearer();
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
            tools.jackson.databind.JsonNode d = details(id, bearer);
            if (d == null) throw new IllegalStateException("no details for " + input);
            return build(input, id, d);        // displaySymbol = input (the suffixed symbol)
        }
        throw new IllegalStateException("no exchange hit");
    }

    private String bearer() { return access.bearer().orElseThrow(); }

    private Instrument lookupIsin(String isin, String bearer) {
        tools.jackson.databind.JsonNode root = access.http().get()
                .uri(uri -> uri.path("/ref/v1/instruments")
                        .queryParam("Keywords", isin).queryParam("AssetTypes", "Stock")
                        .queryParam("$top", 10).build())
                .header("Authorization", bearer)
                .retrieve().body(tools.jackson.databind.JsonNode.class);
        tools.jackson.databind.JsonNode data = root == null ? null : root.path("Data");
        if (data == null || !data.isArray() || data.isEmpty()) throw new IllegalStateException("no hit");

        String country = isin.substring(0, 2);
        Long uic = null;
        for (tools.jackson.databind.JsonNode hit : data) {                 // venue policy: domestic country first
            long hitUic = hit.path("Identifier").asLong(0);
            tools.jackson.databind.JsonNode d = details(hitUic, bearer);
            if (d != null && country.equals(d.path("CountryCode").asString(""))) {
                return build(isin, hitUic, d);
            }
            if (uic == null) uic = hitUic;
        }
        return build(isin, uic, details(uic, bearer));                          // fallback: first hit
    }

    private tools.jackson.databind.JsonNode details(long uic, String bearer) {
        if (uic == 0) return null;
        var cached = detailsCache.peek(uic);
        if (cached.isPresent()) return cached.get();
        tools.jackson.databind.JsonNode d;
        try {
            d = access.http().get()
                    .uri(uri -> uri.path("/ref/v1/instruments/details/" + uic + "/Stock").build())
                    .header("Authorization", bearer)
                    .retrieve().body(tools.jackson.databind.JsonNode.class);
        } catch (RuntimeException e) {           // one venue's details 404ing must not abort the whole scan; transient failure not cached
            log.debug("saxo details lookup failed for uic {}: {}", uic, e.toString());
            return null;
        }
        if (d != null) detailsCache.put(uic, d);   // cache successes only
        return d;
    }

    private Instrument build(String rawInput, long uic, tools.jackson.databind.JsonNode d) {
        if (d == null) throw new IllegalStateException("no details");
        return new Instrument(rawInput, rawInput, d.path("Isin").asString(null), d.path("Mic").asString(null),
                d.path("ExchangeId").asString(null), d.path("CurrencyCode").asString(null),
                uic, d.path("CountryCode").asString(null), "Stock", true,
                d.path("PriceToContractFactor").asDouble(1.0));
    }
}
