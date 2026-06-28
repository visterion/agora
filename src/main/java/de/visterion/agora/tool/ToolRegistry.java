package de.visterion.agora.tool;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private final Map<String, AgoraTool> byName;

    public ToolRegistry(List<AgoraTool> tools) {
        this.byName = tools.stream().collect(Collectors.toMap(
                AgoraTool::name, Function.identity(),
                (a, b) -> { throw new IllegalStateException("Duplicate tool name: " + a.name()); },
                LinkedHashMap::new));
    }

    public List<String> names() { return List.copyOf(byName.keySet()); }

    public List<AgoraTool> all() { return List.copyOf(byName.values()); }

    public AgoraTool get(String name) {
        AgoraTool t = byName.get(name);
        if (t == null) throw new ToolNotFoundException(name);
        return t;
    }

    public ToolResult invoke(String name, JsonNode args) {
        return get(name).call(args);
    }
}
