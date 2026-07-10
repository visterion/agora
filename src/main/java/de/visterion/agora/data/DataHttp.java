package de.visterion.agora.data;

import org.springframework.http.client.JdkClientHttpRequestFactory;

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

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

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
}
