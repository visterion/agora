package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Shared post-fetch step for {@code get_indicators} and {@code get_indicators_batch}: normalise
 * the raw provider bar list, ask {@link ExchangeSessions} whether the newest bar belongs to a
 * trading day that is still running, and evaluate the indicator specs over the completed bars
 * only. Both tools call {@link #evaluate} exactly once per symbol so they cannot disagree about
 * which bar is the newest completed one.
 */
public final class CompletedBarEvaluation {

    private final IndicatorEvaluator evaluator;
    private final ExchangeSessions sessions;

    public CompletedBarEvaluation(IndicatorEvaluator evaluator, ExchangeSessions sessions) {
        this.evaluator = evaluator;
        this.sessions = sessions;
    }

    /**
     * @param raw the provider's bar list for one symbol; must be non-empty (callers already
     *            answer the "no data for {symbol}" case before reaching here)
     * @return either {@code evaluator.unavailable(...)} in the "only an in-progress bar" shape,
     *         or {@code evaluator.evaluate(...)} extended with {@code partialBar},
     *         {@code lastCompletedClose}, {@code currentClose}, {@code currentHigh},
     *         {@code currentLow} and {@code sessionZone}
     */
    public ObjectNode evaluate(String symbol, List<OhlcBar> raw, JsonNode specs, int seriesN) {
        // One normalised list for everything below: after de-duplication there is exactly one row
        // per date, so "the last row" and "the newest trading day" are the same element, and the
        // reported close and the reported date can never come from two different bars.
        List<OhlcBar> bars = Ta4jBars.dedupAndSort(raw);
        boolean partial = sessions.isPartial(symbol, bars.getLast().date());
        List<OhlcBar> completed = partial ? bars.subList(0, bars.size() - 1) : bars;
        if (completed.isEmpty()) {
            return evaluator.unavailable(symbol, "only an in-progress bar for " + symbol);
        }

        ObjectNode entry = evaluator.evaluate(symbol, completed, specs, seriesN);
        OhlcBar live = bars.getLast();
        entry.put("partialBar", partial);
        entry.put("lastCompletedClose", completed.getLast().close());
        entry.put("currentClose", live.close());     // the live print, partial or not
        entry.put("currentHigh", live.high());
        entry.put("currentLow", live.low());
        entry.put("sessionZone", sessions.zoneId(symbol));
        return entry;
    }
}
