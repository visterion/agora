package de.visterion.agora.research;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.helpers.TypicalPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.num.Num;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Resolves a JSON indicator-spec tree against the registry. A spec is either a
 *  catalog name (string) or {name, params?, of?, label?}; 'of' is a price source
 *  or a nested spec. Per-spec problems raise SpecException with a consumer-facing
 *  message — the calling tool turns them into per-entry errors, never a crash. */
public class IndicatorExpressionResolver {

    public static final int MAX_DEPTH = 5;

    private static final Set<String> PRICE_SOURCES =
            Set.of("close", "open", "high", "low", "volume", "typical");

    public static class SpecException extends RuntimeException {
        public SpecException(String message) { super(message); }
    }

    public record Resolved(String label, IndicatorDef def,
                           Map<String, Indicator<Num>> outputs, int minBars) {}

    private final IndicatorRegistry registry;

    public IndicatorExpressionResolver(IndicatorRegistry registry) {
        this.registry = registry;
    }

    public Resolved resolve(JsonNode spec, BarSeries series) {
        return resolve(spec, series, 1);
    }

    private Resolved resolve(JsonNode spec, BarSeries series, int depth) {
        if (depth > MAX_DEPTH) throw new SpecException("expression too deep (max " + MAX_DEPTH + ")");

        String name;
        JsonNode paramsNode = null;
        JsonNode ofNode = null;
        String explicitLabel = null;
        if (spec != null && spec.isString()) {
            name = spec.asString();
        } else if (spec != null && spec.isObject()) {
            if (!spec.hasNonNull("name") || !spec.get("name").isString()) {
                throw new SpecException("indicator spec needs a name");
            }
            name = spec.get("name").asString();
            paramsNode = spec.get("params");
            ofNode = spec.get("of");
            if (spec.hasNonNull("label")) {
                if (!spec.get("label").isString()) throw new SpecException("label must be a string");
                explicitLabel = spec.get("label").asString();
            }
        } else {
            throw new SpecException("indicator spec must be a string or an object");
        }

        IndicatorDef def = registry.find(name).orElseThrow(() ->
                new SpecException("unknown indicator '" + name + "' — see list_indicators"));
        ResolvedParams params = resolveParams(def, paramsNode);

        Indicator<Num>[] inputs;
        String subLabel = null;
        int subMinBars = 0;
        if (def.inputs() == 0) {
            if (ofNode != null && !ofNode.isNull()) {
                throw new SpecException("indicator '" + name
                        + "' works on the bar series and does not accept 'of'");
            }
            inputs = noInputs();
        } else if (ofNode == null || ofNode.isNull()) {
            inputs = one(priceSource("close", series));
        } else if (ofNode.isString() && PRICE_SOURCES.contains(ofNode.asString())) {
            subLabel = ofNode.asString();
            inputs = one(priceSource(subLabel, series));
        } else {
            Resolved sub = resolve(ofNode, series, depth + 1);
            if (!sub.def().singleOutput()) {
                throw new SpecException("indicator '" + sub.def().name()
                        + "' has multiple outputs and cannot be used as input");
            }
            subLabel = sub.label();
            subMinBars = sub.minBars();
            inputs = one(sub.outputs().values().iterator().next());
        }

        Map<String, Indicator<Num>> outputs;
        try {
            outputs = def.factory().create(series, inputs, params);
        } catch (RuntimeException e) {
            throw new SpecException("invalid parameters for '" + name + "': " + e.getMessage());
        }

        String label = explicitLabel != null ? explicitLabel
                : subLabel == null ? name : name + "(" + subLabel + ")";
        // research low (c): composition must be additive, not max. The outer indicator needs
        // a full window of *stable* inner values, not just the inner's first stable point — so
        // outer(inner) requires innerMin + outerWindow - 1 bars in total. Every minBars formula
        // in this catalog follows the "1 + rawWindow" convention (rawWindow = the param(s) that
        // actually drive convergence, e.g. period, possibly x4 for recursive filters), so
        // ownMinBars - 1 recovers rawWindow, and subMinBars + (ownMinBars - 1) - 1 is the total —
        // i.e. subMinBars + ownMinBars - 2. subMinBars is 0 for a plain price-source input
        // ('of' omitted or a price source string), which keeps the old (non-additive) behavior.
        int ownMinBars = def.minBars().applyAsInt(params);
        int minBars = subMinBars > 0 ? Math.max(1, subMinBars + ownMinBars - 2) : ownMinBars;
        return new Resolved(label, def, outputs, minBars);
    }

    private ResolvedParams resolveParams(IndicatorDef def, JsonNode paramsNode) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (ParamDef pd : def.params()) values.put(pd.name(), pd.defaultValue());

        if (paramsNode != null && !paramsNode.isNull()) {
            if (!paramsNode.isObject()) throw new SpecException("params must be an object");
            for (Map.Entry<String, JsonNode> e : paramsNode.properties()) {
                ParamDef pd = def.params().stream()
                        .filter(d -> d.name().equals(e.getKey())).findFirst()
                        .orElseThrow(() -> new SpecException("unknown param '" + e.getKey()
                                + "' for '" + def.name() + "'"));
                JsonNode valueNode = e.getValue();
                BigDecimal v;
                if (pd.type() == ParamDef.Type.INT) {
                    if (!valueNode.isNumber() || !valueNode.canConvertToExactIntegral()) {
                        throw new SpecException("param '" + pd.name() + "' for '" + def.name()
                                + "' must be an integer");
                    }
                    v = BigDecimal.valueOf(valueNode.asInt());
                } else {
                    if (!valueNode.isNumber()) {
                        throw new SpecException("param '" + pd.name() + "' for '" + def.name()
                                + "' must be a number");
                    }
                    v = new BigDecimal(valueNode.asString());
                }
                if ((pd.min() != null && v.compareTo(pd.min()) < 0)
                        || (pd.max() != null && v.compareTo(pd.max()) > 0)) {
                    throw new SpecException("param '" + pd.name() + "' out of range ["
                            + pd.min() + ".." + pd.max() + "] for '" + def.name() + "'");
                }
                values.put(pd.name(), v);
            }
        }
        return new ResolvedParams(values);
    }

    private static Indicator<Num> priceSource(String src, BarSeries series) {
        return switch (src) {
            case "close" -> new ClosePriceIndicator(series);
            case "open" -> new OpenPriceIndicator(series);
            case "high" -> new HighPriceIndicator(series);
            case "low" -> new LowPriceIndicator(series);
            case "volume" -> new VolumeIndicator(series);
            case "typical" -> new TypicalPriceIndicator(series);
            default -> throw new SpecException("unknown price source '" + src + "'");
        };
    }

    @SuppressWarnings("unchecked")
    private static Indicator<Num>[] noInputs() { return new Indicator[0]; }

    @SuppressWarnings("unchecked")
    private static Indicator<Num>[] one(Indicator<Num> ind) { return new Indicator[]{ind}; }
}
