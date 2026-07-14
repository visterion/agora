package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.data.DataHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Shared Finnhub REST access: holds the base RestClient + API key for the fetch/finnhub family. */
@Component
public class FinnhubClient {

    private final RestClient http;
    private final String apiKey;

    @Autowired
    public FinnhubClient(@Value("${agora.data.finnhub.base-url:https://finnhub.io/api/v1}") String baseUrl,
                         @Value("${agora.data.finnhub.key:}") String apiKey,
                         @Value("${agora.fetch.timeout-ms:15000}") long timeoutMs) {
        this.http = DataHttp.clientBuilder(timeoutMs)
                .baseUrl(baseUrl)
                .build();
        this.apiKey = apiKey;
    }

    // Test ctor. Public so test code in sibling packages (e.g. fetch.split) can construct it directly.
    public FinnhubClient(RestClient http, String apiKey) { this.http = http; this.apiKey = apiKey; }

    public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    public RestClient http() { return http; }
    public String token() { return apiKey; }

    /** Header name Finnhub expects the API key in (H8: never as a {@code token=} query param,
     *  which leaks into transport-exception messages that embed the full request URI). */
    public static final String TOKEN_HEADER = "X-Finnhub-Token";
}
