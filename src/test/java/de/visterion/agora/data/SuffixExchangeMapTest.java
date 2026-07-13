package de.visterion.agora.data;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class SuffixExchangeMapTest {
    @Test void everyMappedExchangeIdExistsInTheFixture() throws Exception {
        JsonNode root = new ObjectMapper().readTree(
                Files.readString(Path.of("src/test/resources/saxo/exchanges-2026-07-13.json")));
        Set<String> known = new HashSet<>();
        for (JsonNode e : root.path("Data")) known.add(e.path("ExchangeId").asString(""));
        assertThat(known).containsAll(SaxoInstrumentResolver.SUFFIX_TO_EXCHANGE.values());
    }
}
