package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes deterministic technical-analysis indicators from daily OHLC history.
 *
 * <p>Ported from Dracul's {@code ExitIndicatorService}, dropping all entry/position coupling:
 * no {@code entryPrice}, no {@code verdictCreatedAt}, no {@code horizon}, no {@code gainLossPct},
 * no {@code daysHeld}, no {@code horizonElapsed}, no {@code firedRules}.</p>
 *
 * <p>ATR = simple SMA of the last {@code atrPeriod} True Range values.
 * MA names are {@code maFast}/{@code maSlow} (vs Dracul's ma50/ma200) with configurable periods.</p>
 *
 * <p>This class is pure Java — no Spring, no database dependencies.</p>
 */
public class IndicatorService {

    /** Configuration parameters for indicator computation. */
    public record Params(
            int atrPeriod,           // number of TR bars to average (e.g. 22)
            BigDecimal atrMultiple,  // Chandelier multiplier (e.g. 3.0)
            int maFast,              // fast MA period (e.g. 50)
            int maSlow,              // slow MA period (e.g. 200)
            int minBarsFor52w        // minimum bars for 52-week window flag (prod ~250)
    ) {
        public Params {
            if (atrPeriod <= 0 || maFast <= 0 || maSlow <= maFast)
                throw new IllegalArgumentException("invalid IndicatorService.Params");
        }
    }

    private static final MathContext MC = MathContext.DECIMAL64;

    private final Params defaultParams;

    public IndicatorService(Params defaultParams) {
        this.defaultParams = defaultParams;
    }

    /**
     * Computes technical-analysis indicators.
     *
     * @param bars   OHLC history, oldest first; may be empty or null
     * @param params computation parameters
     * @return computed indicators; never throws even on empty/short history
     */
    public Indicators compute(List<OhlcBar> bars, Params params) {
        // --- Empty / degenerate case ---
        if (bars == null || bars.isEmpty()) {
            return new Indicators(
                    null,
                    null, false,
                    null, false,
                    null, false,
                    null, false,
                    "NEUTRAL",
                    null, null, false);
        }

        int n = bars.size();
        OhlcBar last = bars.get(n - 1);
        BigDecimal currentClose = last.close();

        // --- True Range values (index 1..n-1 relative to bars list) ---
        // TR_i = max(high_i - low_i, |high_i - prevClose|, |low_i - prevClose|)
        List<BigDecimal> trValues = new ArrayList<>(n - 1);
        for (int i = 1; i < n; i++) {
            OhlcBar bar = bars.get(i);
            BigDecimal prevClose = bars.get(i - 1).close();
            BigDecimal hl = bar.high().subtract(bar.low()).abs();
            BigDecimal hpc = bar.high().subtract(prevClose).abs();
            BigDecimal lpc = bar.low().subtract(prevClose).abs();
            BigDecimal tr = hl.max(hpc).max(lpc);
            trValues.add(tr);
        }

        // --- ATR: SMA of last atrPeriod TR values ---
        // trValues.size() == bars.size() - 1, so atrAvailable iff bars.size() > atrPeriod
        boolean atrAvailable = trValues.size() >= params.atrPeriod();
        BigDecimal atr = null;
        if (atrAvailable) {
            List<BigDecimal> lastTrs = trValues.subList(trValues.size() - params.atrPeriod(), trValues.size());
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal tr : lastTrs) sum = sum.add(tr);
            atr = sum.divide(BigDecimal.valueOf(params.atrPeriod()), MC);
        }

        // --- Chandelier stop = highestHigh(last atrPeriod bars) - atrMultiple * atr ---
        BigDecimal chandelierStop = null;
        boolean chandelierBreached = false;
        if (atrAvailable) {
            // last atrPeriod bars: from index (n - atrPeriod) to (n-1)
            int startIdx = n - params.atrPeriod();
            BigDecimal highestHigh = bars.get(startIdx).high();
            for (int i = startIdx + 1; i < n; i++) {
                if (bars.get(i).high().compareTo(highestHigh) > 0) {
                    highestHigh = bars.get(i).high();
                }
            }
            chandelierStop = highestHigh.subtract(params.atrMultiple().multiply(atr, MC));
            chandelierBreached = currentClose.compareTo(chandelierStop) < 0;
        }

        // --- Moving averages ---
        BigDecimal maFast = null;
        boolean maFastAvailable = n >= params.maFast();
        if (maFastAvailable) {
            maFast = sma(bars, n - params.maFast(), n);
        }

        BigDecimal maSlow = null;
        boolean maSlowAvailable = n >= params.maSlow();
        if (maSlowAvailable) {
            maSlow = sma(bars, n - params.maSlow(), n);
        }

        // --- MA cross state ---
        String maCrossState = "NEUTRAL";
        if (maFastAvailable && maSlowAvailable) {
            if (maFast.compareTo(maSlow) < 0) {
                maCrossState = "DEATH_CROSS";
            } else {
                maCrossState = "BULLISH";
            }
        }

        // --- 52-week high/low (computed from all available bars; flag reflects threshold) ---
        BigDecimal high52w = bars.get(0).high();
        BigDecimal low52w = bars.get(0).low();
        for (int i = 1; i < n; i++) {
            if (bars.get(i).high().compareTo(high52w) > 0) high52w = bars.get(i).high();
            if (bars.get(i).low().compareTo(low52w) < 0) low52w = bars.get(i).low();
        }
        boolean window52wAvailable = n >= params.minBarsFor52w();

        return new Indicators(
                currentClose,
                atr, atrAvailable,
                chandelierStop, chandelierBreached,
                maFast, maFastAvailable,
                maSlow, maSlowAvailable,
                maCrossState,
                high52w, low52w, window52wAvailable);
    }

    /**
     * Convenience overload using the default params.
     */
    public Indicators compute(List<OhlcBar> bars) {
        return compute(bars, defaultParams);
    }

    // --- Helpers ---

    /** SMA of close prices from bars[fromIdx] (inclusive) to bars[toIdx] (exclusive). */
    private static BigDecimal sma(List<OhlcBar> bars, int fromIdx, int toIdx) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = fromIdx; i < toIdx; i++) sum = sum.add(bars.get(i).close());
        return sum.divide(BigDecimal.valueOf(toIdx - fromIdx), MC);
    }
}
