package de.visterion.agora.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndicatorCatalogConfigTest {

    @Test
    void beanContainsBuiltinsAndYamlEntries() {
        var reg = new IndicatorCatalogConfig().indicatorRegistry("");
        assertThat(reg.find("atr")).isPresent();          // Java composite
        assertThat(reg.find("macd")).isPresent();          // Java composite
        assertThat(reg.find("rsi")).isPresent();           // YAML
        assertThat(reg.find("parabolic_sar")).isPresent(); // YAML, no params
        assertThat(reg.all().size()).isGreaterThanOrEqualTo(26);
    }

    @Test
    void operatorFileOverridesBuiltins(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("custom.yaml");
        Files.writeString(f, """
                indicators:
                  rsi:
                    class: org.ta4j.core.indicators.RSIIndicator
                    description: "operator override"
                    inputs: 1
                    params:
                      - { name: period, default: 7 }
                  vwma:
                    class: org.ta4j.core.indicators.averages.VWMAIndicator
                    description: "Volume Weighted MA (operator-added)"
                    inputs: 1
                    params:
                      - { name: period, default: 20 }
                """);
        var reg = new IndicatorCatalogConfig().indicatorRegistry(f.toString());
        assertThat(reg.find("rsi").get().description()).isEqualTo("operator override");
        assertThat(reg.find("rsi").get().params().getFirst().defaultValue()).isEqualByComparingTo("7");
        assertThat(reg.find("vwma")).isPresent();
    }

    @Test
    void missingOperatorFileIsLoggedNotFatal() {
        var reg = new IndicatorCatalogConfig().indicatorRegistry("/nonexistent/path.yaml");
        assertThat(reg.find("rsi")).isPresent(); // built-ins survive
    }

    @Test
    void validateRemovesBrokenEntries() {
        var reg = new IndicatorRegistry();
        BuiltinIndicators.defs().forEach(reg::register);
        reg.register(new IndicatorDef("broken", "always throws", List.of(), 0, List.of("value"),
                p -> 1, (s, in, p) -> { throw new IllegalStateException("boom"); }));
        IndicatorCatalogConfig.validate(reg);
        assertThat(reg.find("broken")).isEmpty();
        assertThat(reg.find("atr")).isPresent();
    }
}
