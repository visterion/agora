package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.NonUsSuffixes;
import de.visterion.agora.data.ProviderErrors;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.function.LongSupplier;

/** Company profile for a symbol via Finnhub /stock/profile2 (whole object passthrough), cached per-family. */
@Component
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final FinnhubClient client;
    private final TtlCache<String, Profile> cache;
    private final Set<String> nonUsSuffixes;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public ProfileService(FinnhubClient client,
                          @Value("${agora.data.cache.ttl.fundamentals-seconds:21600}") long ttlSeconds,
                          @Value("${agora.fundamentals.non-us-suffixes:DE,MI,TO,L,T,HK,PA,AS,SW,AX,ST,CO,OL,HE,MC,BR,LS,VI,IR,NZ}") String nonUsSuffixesCsv) {
        this(client, ttlSeconds, System::currentTimeMillis, NonUsSuffixes.parse(nonUsSuffixesCsv));
    }

    ProfileService(FinnhubClient client, long ttlSeconds, LongSupplier now) {
        this(client, ttlSeconds, now, NonUsSuffixes.DEFAULT);
    }

    ProfileService(FinnhubClient client, long ttlSeconds, LongSupplier now, Set<String> nonUsSuffixes) {
        this.client = client;
        this.cache = new TtlCache<>(ttlSeconds * 1000L, 4096, now);
        this.nonUsSuffixes = nonUsSuffixes;
    }

    public Profile profile(String symbol) {
        if (NonUsSuffixes.isNonUs(symbol, nonUsSuffixes)) return new Profile(symbol, mapper.createObjectNode());
        if (!client.configured())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null);
        return cache.get("profile:" + symbol, () -> fetch(symbol));
    }

    private Profile fetch(String symbol) {
        JsonNode body;
        try {
            body = client.http().get()
                    .uri(uri -> uri.path("/stock/profile2")
                            .queryParam("symbol", symbol)
                            .build())
                    .header(FinnhubClient.TOKEN_HEADER, client.token())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("finnhub profile request failed for {}", symbol, e);
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    ProviderErrors.categorize("finnhub profile", e), e);
        }
        if (body == null || !body.isObject() || body.isEmpty())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no profile for " + symbol, null);
        return new Profile(symbol, body);
    }
}
