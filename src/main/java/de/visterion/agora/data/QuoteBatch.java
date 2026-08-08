package de.visterion.agora.data;

import java.util.Map;

/**
 * Outcome of a batch quote lookup: what resolved, and WHY each failure failed.
 *
 * <p>Both maps are keyed by the RAW request symbol — never by the uppercased cache key. A
 * consumer that looks a reason up under a different spelling finds nothing, and "every reason
 * was NOT_FOUND" is then vacuously true over an empty set: a real outage would be reported as
 * "symbol does not exist".
 */
public record QuoteBatch(Map<String, Quote> resolved,
                         Map<String, MarketDataException.Kind> failed) {

    public QuoteBatch {
        resolved = Map.copyOf(resolved);
        failed = Map.copyOf(failed);
    }
}
