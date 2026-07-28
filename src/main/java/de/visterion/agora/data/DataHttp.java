package de.visterion.agora.data;

import de.visterion.agora.observability.ProviderCallLogger;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HTTP plumbing shared by JDK-{@link HttpClient}-based data/fetch providers (M-D8): every
 * caller here previously set only a read timeout via {@link JdkClientHttpRequestFactory}'s
 * no-arg constructor, leaving the JDK default (effectively unbounded) connect timeout in
 * place. A dead/firewalled host would then hang on TCP connect far longer than the configured
 * read timeout, defeating the "fail fast into the next provider" intent of that timeout.
 */
public final class DataHttp {

    /**
     * TCP connect timeout applied to every client built here. Public because it is a real,
     * non-optional term in any "one attempt fits inside budget X" arithmetic: a caller that
     * budgets only for its read timeout under-counts a slow or blackholed connect by exactly
     * this much (see {@code de.visterion.agora.fetch.earnings.EarningsBudgetPolicy}).
     */
    public static final long CONNECT_TIMEOUT_MS = 3_000L;

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(CONNECT_TIMEOUT_MS);

    private DataHttp() {}

    /** Builds a {@link JdkClientHttpRequestFactory} with both a 3s connect timeout and the given read timeout. */
    public static JdkClientHttpRequestFactory requestFactory(long readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return rf;
    }

    /** RestClient.Builder pre-wired with the 3s-connect/read-timeout factory AND the provider-call logging interceptor. */
    public static RestClient.Builder clientBuilder(long readTimeoutMs) {
        return RestClient.builder()
                .requestFactory(requestFactory(readTimeoutMs))
                .requestInterceptor(ProviderCallLogger.INSTANCE);
    }

    /** Same as {@link #clientBuilder(long)} but with {@code first} ahead of the call logger, so
     *  a throttle's wait is not billed to the provider's measured latency. */
    public static RestClient.Builder clientBuilder(long readTimeoutMs, ClientHttpRequestInterceptor first) {
        return RestClient.builder()
                .requestFactory(requestFactory(readTimeoutMs))
                .requestInterceptor(first)
                .requestInterceptor(ProviderCallLogger.INSTANCE);
    }
}
