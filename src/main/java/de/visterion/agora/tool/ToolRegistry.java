package de.visterion.agora.tool;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

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
        AgoraTool tool = get(name);              // throws ToolNotFoundException → 404 (unchanged)
        try {
            return tool.call(args);
        } catch (ToolParams.InvalidArgumentException | IllegalArgumentException e) {
            // caller-supplied-argument problem: safe to echo back (never null, no internals leaked).
            return ToolResult.unavailable("invalid argument: " + safeMessage(e));
        } catch (RuntimeException e) {
            // M-X7: everything else is an internal bug or an upstream we don't want to fingerprint
            // to the client. Log the full exception server-side; never surface e.getMessage().
            log.error("tool '{}' failed", name, e);
            return ToolResult.unavailable("internal error in tool '" + name + "'");
        }
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
