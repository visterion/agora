package de.visterion.agora.tools;

import de.visterion.agora.research.BuiltinIndicators;
import de.visterion.agora.research.IndicatorRegistry;
import de.visterion.agora.research.YamlIndicatorCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

class ListIndicatorsToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static IndicatorRegistry registry() {
        var reg = new IndicatorRegistry();
        BuiltinIndicators.defs().forEach(reg::register);
        try (InputStream in = ListIndicatorsToolTest.class
                .getResourceAsStream("/indicators-catalog.yaml")) {
            YamlIndicatorCatalog.load(in).forEach(reg::register);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return reg;
    }

    private final ListIndicatorsTool tool = new ListIndicatorsTool(registry());

    private static JsonNode entry(JsonNode out, String name) {
        for (JsonNode e : out.get("indicators")) {
            if (name.equals(e.get("name").asString())) return e;
        }
        return null;
    }

    @Test
    void listsFullCatalogWithMetadata() {
        var r = tool.call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("count").asInt()).isGreaterThanOrEqualTo(26);

        JsonNode rsi = entry(r.output(), "rsi");
        assertThat(rsi).isNotNull();
        assertThat(rsi.get("description").asString()).isEqualTo("Relative Strength Index");
        assertThat(rsi.get("inputs").asInt()).isEqualTo(1);
        assertThat(rsi.get("params").get(0).get("name").asString()).isEqualTo("period");
        assertThat(rsi.get("params").get(0).get("default").asInt()).isEqualTo(14);
        assertThat(rsi.get("params").get(0).get("type").asString()).isEqualTo("int");

        JsonNode macd = entry(r.output(), "macd");
        assertThat(macd.get("outputs")).hasSize(3);
    }

    @Test
    void nullArgsWork() {
        assertThat(tool.call(null).available()).isTrue();
    }

    @Test
    void nameFilter() {
        var args = mapper.createObjectNode().put("name", "boll");
        var r = tool.call(args);
        assertThat(r.output().get("count").asInt()).isEqualTo(1);
        assertThat(r.output().get("indicators").get(0).get("name").asString()).isEqualTo("bollinger");
    }

    @Test
    void namespaceIsGeneral() {
        assertThat(tool.namespace()).isEqualTo("general");
    }
}
