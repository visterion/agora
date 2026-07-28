package de.visterion.agora.data;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that every Yahoo client injects the SAME user-agent property. The two-class
 * split (bot UA for price/chart, browser UA for crumb) is deliberately gone; this test
 * fails if anyone reintroduces a second property.
 */
class YahooUserAgentPinTest {

    private static final List<Class<?>> YAHOO_CLIENTS = List.of(
            de.visterion.agora.data.YahooMarketDataProvider.class,
            de.visterion.agora.data.FxService.class,
            de.visterion.agora.data.IntradayService.class,
            de.visterion.agora.fetch.earnings.YahooEarningsProvider.class,
            de.visterion.agora.research.fundamentals.YahooCrumbClient.class);

    @Test void allYahooClientsInjectTheSameUserAgentProperty() {
        List<String> found = new ArrayList<>();
        for (Class<?> c : YAHOO_CLIENTS) {
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                for (Parameter p : ctor.getParameters()) {
                    Value v = p.getAnnotation(Value.class);
                    if (v != null && v.value().contains("user-agent")) found.add(v.value());
                }
            }
        }
        assertThat(found).isNotEmpty();
        assertThat(found).allSatisfy(expr ->
                assertThat(expr).contains("agora.data.yahoo.user-agent"));
        assertThat(found).noneSatisfy(expr ->
                assertThat(expr).contains("crumb-user-agent"));
    }
}
