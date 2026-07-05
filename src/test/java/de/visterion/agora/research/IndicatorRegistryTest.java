package de.visterion.agora.research;

import org.junit.jupiter.api.Test;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndicatorRegistryTest {

    private static IndicatorDef def(String name, String description) {
        return new IndicatorDef(name, description,
                List.of(ParamDef.intParam("period", 14, 1, 500)),
                1, List.of("value"),
                p -> p.getInt("period") + 1,
                (series, inputs, p) -> Map.of("value", new ClosePriceIndicator(series)));
    }

    @Test
    void registerAndFind() {
        var reg = new IndicatorRegistry();
        reg.register(def("rsi", "Relative Strength Index"));
        assertThat(reg.find("rsi")).isPresent();
        assertThat(reg.find("rsi").get().description()).isEqualTo("Relative Strength Index");
        assertThat(reg.find("nope")).isEmpty();
        assertThat(reg.all()).hasSize(1);
    }

    @Test
    void registerOverridesOnNameCollision() {
        var reg = new IndicatorRegistry();
        reg.register(def("rsi", "built-in"));
        reg.register(def("rsi", "operator override"));
        assertThat(reg.all()).hasSize(1);
        assertThat(reg.find("rsi").get().description()).isEqualTo("operator override");
    }

    @Test
    void removeDeletesEntry() {
        var reg = new IndicatorRegistry();
        reg.register(def("rsi", "x"));
        reg.remove("rsi");
        assertThat(reg.find("rsi")).isEmpty();
    }

    @Test
    void defValidation() {
        assertThatThrownBy(() -> new IndicatorDef("x", "d", List.of(), 2, List.of("value"),
                p -> 1, (s, in, p) -> Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndicatorDef("x", "d", List.of(), 0, List.of(),
                p -> 1, (s, in, p) -> Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvedParamsAccessors() {
        var p = new ResolvedParams(Map.of(
                "period", new BigDecimal("14"), "k", new BigDecimal("2.5")));
        assertThat(p.getInt("period")).isEqualTo(14);
        assertThat(p.getDecimal("k")).isEqualByComparingTo("2.5");
    }

    @Test
    void defaultsBuildsFromParamDefs() {
        var p = ResolvedParams.defaults(List.of(
                ParamDef.intParam("period", 14, 1, 500),
                ParamDef.decimalParam("k", "2.0", "0.1", "10")));
        assertThat(p.getInt("period")).isEqualTo(14);
        assertThat(p.getDecimal("k")).isEqualByComparingTo("2.0");
    }
}
