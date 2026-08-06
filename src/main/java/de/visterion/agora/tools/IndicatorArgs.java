package de.visterion.agora.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.List;

/**
 * The argument half of the indicator tools — {@code indicators} / {@code series} /
 * {@code fetchDays}, normalised and bounds-checked once for {@code get_indicators} and
 * {@code get_indicators_batch}. Shared so the two tools cannot accept different inputs for the
 * same computation.
 *
 * @param specs   the indicator spec array (caller's, or the configured default palette)
 * @param seriesN trailing values to emit per indicator (0 = none)
 * @param days    history window to fetch, already clamped
 */
record IndicatorArgs(JsonNode specs, int seriesN, int days) {

    static final int MAX_SPECS = 20;
    static final int MAX_SERIES = 250;
    static final int MAX_FETCH_DAYS = 1825;

    /** @throws IllegalArgumentException with the exact caller-facing message to surface */
    static IndicatorArgs parse(JsonNode args, List<String> defaultIndicators, int defaultFetchDays,
                                ObjectMapper mapper) {
        int seriesN;
        try {
            seriesN = intArg(args, "series", 0);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid series");
        }
        if (seriesN < 0 || seriesN > MAX_SERIES) {
            throw new IllegalArgumentException("series must be 0.." + MAX_SERIES);
        }
        int days;
        try {
            days = intArg(args, "fetchDays", defaultFetchDays);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid fetchDays");
        }
        if (days <= 0) throw new IllegalArgumentException("invalid fetchDays");
        // M-X4: silently clamp — mirrors b5b32a7's days<=1825 clamp on the other tools.
        days = Math.clamp(days, 1, MAX_FETCH_DAYS);

        JsonNode specs;
        if (args != null && args.has("indicators") && !args.get("indicators").isNull()) {
            specs = args.get("indicators");
        } else {
            ArrayNode defaults = mapper.createArrayNode();
            defaultIndicators.forEach(defaults::add);
            specs = defaults;
        }
        if (!specs.isArray()) throw new IllegalArgumentException("indicators must be an array");
        if (specs.isEmpty()) throw new IllegalArgumentException("indicators must not be empty");
        if (specs.size() > MAX_SPECS) {
            throw new IllegalArgumentException("too many indicator specs (max " + MAX_SPECS + ")");
        }
        return new IndicatorArgs(specs, seriesN, days);
    }

    private static int intArg(JsonNode args, String field, int fallback) {
        if (args == null || !args.has(field) || args.get(field).isNull()) return fallback;
        JsonNode node = args.get(field);
        if (!node.isNumber() || !node.canConvertToExactIntegral()) {
            throw new IllegalArgumentException("field '" + field + "' must be an integer");
        }
        return node.asInt();
    }
}
