package de.visterion.agora.data;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-D8: DataHttp must set BOTH a connect timeout and the caller's read timeout — previously
 * every data/fetch provider used {@code new JdkClientHttpRequestFactory()}, which leaves the
 * JDK's effectively-unbounded default connect timeout in place. A dead/firewalled host would
 * hang on TCP connect far longer than the configured read timeout.
 */
class DataHttpTest {

    @Test
    void requestFactoryCarriesConnectTimeoutAndReadTimeout() throws Exception {
        JdkClientHttpRequestFactory rf = DataHttp.requestFactory(4_000L);

        Field httpClientField = JdkClientHttpRequestFactory.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        HttpClient httpClient = (HttpClient) httpClientField.get(rf);
        assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(3));

        Field readTimeoutField = JdkClientHttpRequestFactory.class.getDeclaredField("readTimeout");
        readTimeoutField.setAccessible(true);
        Duration readTimeout = (Duration) readTimeoutField.get(rf);
        assertThat(readTimeout).isEqualTo(Duration.ofMillis(4_000L));
    }
}
