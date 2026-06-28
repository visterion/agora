package de.visterion.agora.tool;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private final Map<String, AgoraTool> byName = new LinkedHashMap<>();

    public ToolRegistry(List<AgoraTool> tools) {
        for (AgoraTool t : tools) byName.put(t.name(), t);
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
