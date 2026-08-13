package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.data.DataHttp;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.NonUsSuffixes;
import de.visterion.agora.data.ProviderErrors;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Company profile for a symbol via Finnhub /stock/profile2 (whole object passthrough), cached
 * per-family.
 *
 * <p>Unlike {@link FundamentalsService} and {@link EstimatesService} — which share {@link
 * FinnhubClient}'s default {@link FinnhubRateLimiter.Mode#WAIT} interceptor because they have no
 * fallback path — this service builds its own {@link RestClient} in {@link
 * FinnhubRateLimiter.Mode#FAIL_FAST}, the same reasoning {@link
 * de.visterion.agora.data.FinnhubMarketDataProvider} already applies to the quote path: a cold
 * in-memory cache after every Agora deploy means dozens of profile calls land right after a
 * {@code /stock/metric} sweep has drained the shared token bucket. Blocking (the {@code WAIT}
 * default, up to {@code max-wait-ms} per call) would hold that bucket's slots away from every
 * other Finnhub caller — including {@code /stock/metric}, which has no alternative and no local
 * degrade. Failing fast instead makes the loss immediate and local: the profile call degrades
 * (empty/absent profile, same as any other Finnhub outage) without starving callers that cannot.
 */
@Component
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final FinnhubClient client;
    private final RestClient profileHttp;
    private final TtlCache<String, Profile> cache;
    private final Set<String> nonUsSuffixes;
    private final ObjectMapper mapper = new ObjectMapper();
    private final YahooCompanyDataSource yahoo;
    private final TtlCache<String, Profile> yahooCache;

    @Autowired
    public ProfileService(FinnhubClient client,
                          @Value("${agora.data.cache.ttl.finnhub-profile-seconds:604800}") long ttlSeconds,
                          @Value("${agora.fundamentals.non-us-suffixes:DE,MI,TO,L,T,HK,PA,AS,SW,AX,ST,CO,OL,HE,MC,BR,LS,VI,IR,NZ}") String nonUsSuffixesCsv,
                          @Value("${agora.data.cache.ttl.company-profile-seconds:604800}") long yahooTtlSeconds,
                          @Value("${agora.data.finnhub.base-url:https://finnhub.io/api/v1}") String baseUrl,
                          @Value("${agora.fetch.timeout-ms:15000}") long timeoutMs,
                          FinnhubRateLimiter rateLimiter,
                          YahooCompanyDataSource yahoo) {
        this(client, ttlSeconds, System::currentTimeMillis, NonUsSuffixes.parse(nonUsSuffixesCsv), yahooTtlSeconds,
                DataHttp.clientBuilder(timeoutMs, rateLimiter.withMode(FinnhubRateLimiter.Mode.FAIL_FAST))
                        .baseUrl(baseUrl)
                        .build(),
                yahoo);
    }

    ProfileService(FinnhubClient client, long ttlSeconds, LongSupplier now, long yahooTtlSeconds, YahooCompanyDataSource yahoo) {
        this(client, ttlSeconds, now, NonUsSuffixes.DEFAULT, yahooTtlSeconds, client.http(), yahoo);
    }

    ProfileService(FinnhubClient client, long ttlSeconds, LongSupplier now, Set<String> nonUsSuffixes,
                    long yahooTtlSeconds, RestClient profileHttp, YahooCompanyDataSource yahoo) {
        this.client = client;
        this.profileHttp = profileHttp;
        this.cache = new TtlCache<>(ttlSeconds * 1000L, 4096, now);
        this.nonUsSuffixes = nonUsSuffixes;
        this.yahoo = yahoo;
        this.yahooCache = new TtlCache<>(yahooTtlSeconds * 1000L, 4096, now);
    }

    public Profile profile(String symbol) {
        if (NonUsSuffixes.isNonUs(symbol, nonUsSuffixes)) {
            try {
                return yahooCache.get("profile:" + symbol, () -> yahoo.profile(symbol));
            } catch (MarketDataException e) {
                return new Profile(symbol, mapper.createObjectNode());
            }
        }
        if (!client.configured())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null);
        return cache.get("profile:" + symbol, () -> fetch(symbol));
    }

    private Profile fetch(String symbol) {
        JsonNode body;
        try {
            body = profileHttp.get()
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
