package de.visterion.agora.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Loads IndicatorDefs from a YAML catalog. The YAML supplies the metadata reflection
 *  cannot (defaults, bounds, descriptions); reflection is only the instantiation
 *  mechanism, restricted to a package whitelist. Invalid entries are logged and
 *  skipped — this loader never throws. YAML entries support int params only;
 *  anything richer belongs in BuiltinIndicators (Java). */
public final class YamlIndicatorCatalog {

    private static final Logger log = LoggerFactory.getLogger(YamlIndicatorCatalog.class);
    private static final String WHITELIST_PREFIX = "org.ta4j.core.indicators.";

    private YamlIndicatorCatalog() {}

    public static List<IndicatorDef> load(InputStream in) {
        List<IndicatorDef> defs = new ArrayList<>();
        Object root;
        try {
            root = new Yaml().load(in);
        } catch (RuntimeException e) {
            log.error("indicator catalog: unparseable YAML: {}", e.toString());
            return defs;
        }
        if (!(root instanceof Map<?, ?> rootMap)
                || !(rootMap.get("indicators") instanceof Map<?, ?> indicators)) {
            return defs;
        }
        for (Map.Entry<?, ?> e : indicators.entrySet()) {
            String name = String.valueOf(e.getKey());
            try {
                if (!(e.getValue() instanceof Map<?, ?> entry)) {
                    throw new IllegalArgumentException("entry must be a mapping");
                }
                defs.add(toDef(name, entry));
            } catch (Exception ex) {
                log.error("indicator catalog: skipping '{}': {}", name, ex.toString());
            }
        }
        return defs;
    }

    private static IndicatorDef toDef(String name, Map<?, ?> entry) throws ReflectiveOperationException {
        String className = String.valueOf(entry.get("class"));
        if (!className.startsWith(WHITELIST_PREFIX)) {
            throw new IllegalArgumentException("class must be under " + WHITELIST_PREFIX);
        }
        Class<?> cls = Class.forName(className);
        if (!Indicator.class.isAssignableFrom(cls)) {
            throw new IllegalArgumentException(className + " does not implement Indicator");
        }

        int inputs = entry.get("inputs") instanceof Number n ? n.intValue() : 0;
        String description = entry.get("description") instanceof String s ? s : name;
        List<ParamDef> params = parseParams(entry.get("params"));
        boolean recursive = parseWarmup(entry.get("warmup"));

        Class<?>[] sig = new Class<?>[1 + params.size()];
        sig[0] = inputs == 1 ? Indicator.class : BarSeries.class;
        Arrays.fill(sig, 1, sig.length, int.class);
        Constructor<?> ctor = cls.getConstructor(sig);

        List<ParamDef> paramsFinal = params;
        IndicatorFactory factory = (series, ins, p) -> {
            Object[] args = new Object[1 + paramsFinal.size()];
            args[0] = sigZeroIsIndicator(ctor) ? ins[0] : series;
            for (int i = 0; i < paramsFinal.size(); i++) {
                args[1 + i] = p.getInt(paramsFinal.get(i).name());
            }
            try {
                @SuppressWarnings("unchecked")
                Indicator<Num> ind = (Indicator<Num>) ctor.newInstance(args);
                return Map.of("value", ind);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("failed to instantiate " + ctor.getDeclaringClass().getName(), ex);
            }
        };

        // H3: ta4j seeds EMA/Wilder recursions (RSI/EMA/ADX/PPO/...) with the raw price at the
        // first stable index, not an SMA seed — the value only converges after several periods.
        // warmup: recursive raises minBars from the exact window (1 + maxPeriod) to a
        // convergence-safe multiple (1 + 4*maxPeriod). Non-recursive (SMA, ROC, window-math)
        // entries keep the exact window (default).
        int warmupMultiplier = recursive ? 4 : 1;
        return new IndicatorDef(name, description, params, inputs, List.of("value"),
                p -> 1 + warmupMultiplier
                        * paramsFinal.stream().mapToInt(d -> p.getInt(d.name())).max().orElse(0),
                factory);
    }

    private static boolean sigZeroIsIndicator(Constructor<?> ctor) {
        return Indicator.class.equals(ctor.getParameterTypes()[0]);
    }

    private static boolean parseWarmup(Object raw) {
        if (raw == null) return false;
        String s = String.valueOf(raw);
        return switch (s) {
            case "exact" -> false;
            case "recursive" -> true;
            default -> throw new IllegalArgumentException("unknown warmup '" + s
                    + "' (expected 'exact' or 'recursive')");
        };
    }

    private static List<ParamDef> parseParams(Object raw) {
        List<ParamDef> params = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return params;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> p)) throw new IllegalArgumentException("param must be a mapping");
            String pname = String.valueOf(p.get("name"));
            String type = p.get("type") instanceof String s ? s : "int";
            if (!"int".equals(type)) {
                throw new IllegalArgumentException("yaml catalog supports int params only (param '" + pname + "')");
            }
            if (!(p.get("default") instanceof Number def)) {
                throw new IllegalArgumentException("param '" + pname + "' needs a numeric default");
            }
            // research low (f): a fractional default (e.g. 14.7) used to be silently truncated
            // via Number#intValue() — fail catalog load instead of shipping a wrong default.
            if (def.doubleValue() != Math.rint(def.doubleValue())) {
                throw new IllegalArgumentException("param '" + pname
                        + "' default must be an integer, got " + def);
            }
            int min = p.get("min") instanceof Number n ? n.intValue() : 1;
            int max = p.get("max") instanceof Number n ? n.intValue() : 10000;
            params.add(ParamDef.intParam(pname, def.intValue(), min, max));
        }
        return params;
    }
}
