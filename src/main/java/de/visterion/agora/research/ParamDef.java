package de.visterion.agora.research;

import java.math.BigDecimal;

/** Declares one indicator parameter: type, default value, and optional bounds. */
public record ParamDef(String name, Type type, BigDecimal defaultValue, BigDecimal min, BigDecimal max) {

    public enum Type { INT, DECIMAL }

    public ParamDef {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("param name required");
        if (type == null) throw new IllegalArgumentException("param type required");
        if (defaultValue == null) throw new IllegalArgumentException("param default required");
    }

    public static ParamDef intParam(String name, int defaultValue, int min, int max) {
        return new ParamDef(name, Type.INT, BigDecimal.valueOf(defaultValue),
                BigDecimal.valueOf(min), BigDecimal.valueOf(max));
    }

    public static ParamDef decimalParam(String name, String defaultValue, String min, String max) {
        return new ParamDef(name, Type.DECIMAL, new BigDecimal(defaultValue),
                min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max));
    }
}
