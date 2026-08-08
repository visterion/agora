package de.visterion.agora.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome of a batch quote lookup: what resolved, and WHY each failure failed.
 *
 * <p>Both maps are keyed by the RAW request symbol — never by the uppercased cache key. A
 * consumer that looks a reason up under a different spelling finds nothing, and "every reason
 * was NOT_FOUND" is then vacuously true over an empty set: a real outage would be reported as
 * "symbol does not exist".
 *
 * <p>{@code resolved} preserves request order — {@code GetQuoteTool} iterates
 * {@code resolved.values()} to emit {@code quotes[]}, and {@code Map.copyOf} would silently
 * replace the caller's request-ordered map with one whose iteration order is unspecified (and
 * salt-randomised per JVM run), reordering a caller's batch response on every restart.
 */
public record QuoteBatch(Map<String, Quote> resolved,
                         Map<String, MarketDataException.Kind> failed) {

    public QuoteBatch {
        resolved = Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
        failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed));
    }
}
