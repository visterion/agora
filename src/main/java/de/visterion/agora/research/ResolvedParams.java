package de.visterion.agora.research;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validated parameter values for one indicator instantiation. */
public record ResolvedParams(Map<String, BigDecimal> values) {

    public ResolvedParams {
        values = Map.copyOf(values);
    }

    public int getInt(String name) { return values.get(name).intValue(); }

    public BigDecimal getDecimal(String name) { return values.get(name); }

    /** All defaults from the given param declarations. */
    public static ResolvedParams defaults(List<ParamDef> defs) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        defs.forEach(d -> m.put(d.name(), d.defaultValue()));
        return new ResolvedParams(m);
    }
}
