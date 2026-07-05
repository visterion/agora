package de.visterion.agora.research;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Name → IndicatorDef catalog. register() replaces on name collision, so later
 *  sources (operator YAML) override earlier ones (built-ins). Pure Java — the
 *  Spring bean is assembled in IndicatorCatalogConfig. */
public class IndicatorRegistry {

    private final Map<String, IndicatorDef> byName = new LinkedHashMap<>();

    public void register(IndicatorDef def) { byName.put(def.name(), def); }

    public Optional<IndicatorDef> find(String name) { return Optional.ofNullable(byName.get(name)); }

    public List<IndicatorDef> all() { return List.copyOf(byName.values()); }

    public void remove(String name) { byName.remove(name); }
}
