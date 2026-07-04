package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.indicators.helpers.TRIndicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Computes deterministic technical-analysis indicators from daily OHLC history.
 *
 * <p>Computes ATR (SMA of True Range), Chandelier stop, moving-average cross,
 * and 52-week high/low range. Backed by ta4j primitives (see {@code Ta4jBars}).
 * All coupling to order management, sizing, or time-in-trade is excluded by design.</p>
 *
 * <p>ATR = simple SMA of the last {@code atrPeriod} True Range values.
 * MA names are {@code maFast}/{@code maSlow} with configurable periods.</p>
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

    /** Returns the default {@link Params} this service was constructed with. */
    public Params defaultParams() {
        return defaultParams;
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
        BarSeries series = Ta4jBars.toSeries(bars);
        int end = series.getEndIndex();

        // --- Current close ---
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        BigDecimal currentClose = close.getValue(end).bigDecimalValue();

        // --- ATR: SMA of the last atrPeriod True Range values ---
        // TR count is n-1 (index 0 has no prior close), so atrAvailable iff (n-1) >= atrPeriod.
        // The guard ensures the SMA window never reaches index 0's TR, so ta4j's index-0
        // TR = high-low convention does not affect the Slice-3 fixtures.
        boolean atrAvailable = (n - 1) >= params.atrPeriod();
        SMAIndicator atrIndicator = new SMAIndicator(new TRIndicator(series), params.atrPeriod());
        BigDecimal atr = null;
        if (atrAvailable) {
            atr = atrIndicator.getValue(end).bigDecimalValue();
        }

        // --- Chandelier stop = highestHigh(last atrPeriod bars) - atrMultiple * atr ---
        BigDecimal chandelierStop = null;
        boolean chandelierBreached = false;
        if (atrAvailable) {
            HighestValueIndicator highestHigh =
                    new HighestValueIndicator(new HighPriceIndicator(series), params.atrPeriod());
            BigDecimal hh = highestHigh.getValue(end).bigDecimalValue();
            chandelierStop = hh.subtract(params.atrMultiple().multiply(atr, MC));
            chandelierBreached = currentClose.compareTo(chandelierStop) < 0;
        }

        // --- Moving averages (SMA of close over the last n periods) ---
        BigDecimal maFast = null;
        boolean maFastAvailable = n >= params.maFast();
        if (maFastAvailable) {
            maFast = new SMAIndicator(close, params.maFast()).getValue(end).bigDecimalValue();
        }

        BigDecimal maSlow = null;
        boolean maSlowAvailable = n >= params.maSlow();
        if (maSlowAvailable) {
            maSlow = new SMAIndicator(close, params.maSlow()).getValue(end).bigDecimalValue();
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
        BigDecimal high52w = new HighestValueIndicator(new HighPriceIndicator(series), n)
                .getValue(end).bigDecimalValue();
        BigDecimal low52w = new LowestValueIndicator(new LowPriceIndicator(series), n)
                .getValue(end).bigDecimalValue();
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
}
