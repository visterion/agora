package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mirrors Dracul's ExitIndicatorServiceTest — pure-TA cases only.
 * Drops: gainLossPct, daysHeld, horizonElapsed, firedRules, TIME_STOP.
 *
 * Small params: Params(atrPeriod=3, atrMultiple=3.0, maFast=2, maSlow=4, minBarsFor52w=5)
 * so availability kicks in on tiny fixtures.
 */
class IndicatorServiceTest {

    // Small params for tiny fixtures
    private static final IndicatorService.Params SMALL_PARAMS =
            new IndicatorService.Params(3, new BigDecimal("3.0"), 2, 4, 5);

    private final IndicatorService svc = new IndicatorService(SMALL_PARAMS);

    /** N bars, close == i+1 (rising), high=close+1, low=close-1. */
    private List<OhlcBar> rising(int n) {
        var bars = new ArrayList<OhlcBar>();
        for (int i = 0; i < n; i++) {
            var c = new BigDecimal(i + 1);
            bars.add(new OhlcBar(LocalDate.of(2025, 1, 1).plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000));
        }
        return bars;
    }

    private OhlcBar flat(int i, int px) {
        var c = new BigDecimal(px);
        return new OhlcBar(LocalDate.of(2025, 1, 1).plusDays(i), c, c, c, c, 1000);
    }

    // -------------------------------------------------------------------------
    // ATR (SMA of True Ranges)
    // -------------------------------------------------------------------------

    /**
     * Exact ATR value on a small fixture.
     *
     * bars (4 bars, rising: close=1,2,3,4; high=close+1, low=close-1):
     *   TR[1] = max(|2-1|, |2-1|, |1-1|) = max(2,1,1) = 2  (high=3,low=1 → hl=2; high=3-prev_close=2; low=1-prev_close=0)
     *   TR[2] = max(|3-2|, |3-2|, |2-2|) = max(2,1,0) = 2  (high=4,low=2 → hl=2; high=4-prev2; low=2-prev2)
     *   TR[3] = max(|4-3|, |4-3|, |3-3|) = max(2,1,0) = 2  (high=5,low=3 → hl=2; high=5-3=2; low=3-3=0)
     *
     * atrPeriod=3 → ATR = (2+2+2)/3 = 2
     * bars.size()=4 > atrPeriod=3 → atrAvailable=true
     */
    @Test
    void atrExactValueSmallFixture() {
        var bars = rising(4);
        var ind = svc.compute(bars, SMALL_PARAMS);
        assertThat(ind.atrAvailable()).isTrue();
        assertThat(ind.atr()).isEqualByComparingTo("2");
        assertThat(ind.currentClose()).isEqualByComparingTo("4");
    }

    @Test
    void atrUnavailableWhenTooFewBars() {
        // 3 bars → trValues.size()=2 < atrPeriod=3 → atrAvailable=false
        var ind = svc.compute(rising(3), SMALL_PARAMS);
        assertThat(ind.atrAvailable()).isFalse();
        assertThat(ind.atr()).isNull();
    }

    // -------------------------------------------------------------------------
    // Chandelier stop + breached
    // -------------------------------------------------------------------------

    /**
     * Flat bars then a crash: chandelier stop is computed from highestHigh of last atrPeriod bars,
     * and close drops well below the stop → chandelierBreached = true.
     *
     * Mirrors Dracul's chandelierBreachWhenCloseBelowStop.
     *
     * Math with atrPeriod=4, atrMultiple=3.0 (custom params to avoid stop==close degeneracy):
     *   bars: flat(100)×4 + crash(close=50, high=100, low=50)
     *   TR[1]=TR[2]=TR[3]=0, TR[4]: hl=50, hpc=0, lpc=50 → TR=50
     *   ATR(period=4) = (0+0+0+50)/4 = 12.5
     *   highestHigh(last 4 bars: indices 1..4) = 100
     *   chandelierStop = 100 − 3×12.5 = 62.5
     *   currentClose=50 < 62.5 → chandelierBreached=true
     */
    @Test
    void chandelierBreachWhenCloseBelowStop() {
        var params = new IndicatorService.Params(4, new BigDecimal("3.0"), 2, 5, 5);
        var bars = new ArrayList<OhlcBar>();
        for (int i = 0; i < 4; i++) bars.add(flat(i, 100));
        bars.add(new OhlcBar(LocalDate.of(2025, 1, 1).plusDays(4),
                new BigDecimal("50"), new BigDecimal("100"), new BigDecimal("50"),
                new BigDecimal("50"), 1000));
        var ind = svc.compute(bars, params);
        assertThat(ind.atrAvailable()).isTrue();
        assertThat(ind.chandelierStop()).isEqualByComparingTo("62.5");
        assertThat(ind.chandelierBreached()).isTrue();
    }

    @Test
    void chandelierNotBreachedWhenCloseAboveStop() {
        // All flat — close == chandelierStop would require: highestHigh - 3*atr < close
        // rising bars: close keeps increasing, chandelier < close
        var bars = rising(5);
        var ind = svc.compute(bars, SMALL_PARAMS);
        assertThat(ind.atrAvailable()).isTrue();
        // on strictly rising bars the close is above the chandelier stop
        assertThat(ind.chandelierBreached()).isFalse();
    }

    // -------------------------------------------------------------------------
    // MA cross: BULLISH / DEATH_CROSS / NEUTRAL
    // -------------------------------------------------------------------------

    /**
     * Rising bars: maFast (avg of last 2) > maSlow (avg of last 4) → BULLISH.
     * Need >= maSlow=4 bars for both to be available.
     */
    @Test
    void maCrossBullishOnRisingBars() {
        var bars = rising(6);
        var ind = svc.compute(bars, SMALL_PARAMS);
        assertThat(ind.maFastAvailable()).isTrue();
        assertThat(ind.maSlowAvailable()).isTrue();
        assertThat(ind.maCrossState()).isEqualTo("BULLISH");
    }

    /**
     * Declining bars: maFast (avg of last 2) < maSlow (avg of last 4) → DEATH_CROSS.
     * Mirrors Dracul's deathCrossDetectedAndFired.
     */
    @Test
    void deathCrossOnDecliningBars() {
        // 6 bars declining: close=6,5,4,3,2,1
        var bars = new ArrayList<OhlcBar>();
        for (int i = 0; i < 6; i++) {
            var c = new BigDecimal(6 - i);
            bars.add(new OhlcBar(LocalDate.of(2024, 1, 1).plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000));
        }
        var ind = svc.compute(bars, SMALL_PARAMS);
        assertThat(ind.maSlowAvailable()).isTrue();
        assertThat(ind.maCrossState()).isEqualTo("DEATH_CROSS");
    }

    @Test
    void maCrossNeutralWhenSlowUnavailable() {
        // Only 3 bars: maFast available (>=2), maSlow not available (<4)
        var ind = svc.compute(rising(3), SMALL_PARAMS);
        assertThat(ind.maFastAvailable()).isTrue();
        assertThat(ind.maSlowAvailable()).isFalse();
        assertThat(ind.maCrossState()).isEqualTo("NEUTRAL");
        assertThat(ind.crossedWithinBars()).isNull();
    }

    // -------------------------------------------------------------------------
    // crossedWithinBars: bars since the fast/slow sign last flipped (research low (i))
    // -------------------------------------------------------------------------

    @Test
    void crossedWithinBarsNullWhenNoSignChangeInWindow() {
        // Strictly rising the whole way: fast > slow always -> no sign flip ever recorded.
        var ind = svc.compute(rising(10), SMALL_PARAMS);
        assertThat(ind.maCrossState()).isEqualTo("BULLISH");
        assertThat(ind.crossedWithinBars()).isNull();
    }

    @Test
    void crossedWithinBarsCountsBarsSinceTheFlip() {
        // Declining then rising: fast/slow sign flips partway through -> crossedWithinBars > 0.
        var bars = new ArrayList<OhlcBar>();
        // 6 declining bars (close 10..5), then 6 rising bars (close 6..11):
        // fast(2)/slow(4) starts DEATH_CROSS, ends BULLISH -> a sign flip must be within the window.
        for (int i = 0; i < 6; i++) bars.add(flat(i, 10 - i));
        for (int i = 0; i < 6; i++) bars.add(flat(6 + i, 5 + i));
        var params = new IndicatorService.Params(3, new BigDecimal("3.0"), 2, 4, 5);
        var ind = svc.compute(bars, params);
        assertThat(ind.maCrossState()).isEqualTo("BULLISH");
        assertThat(ind.crossedWithinBars()).isNotNull();
        assertThat(ind.crossedWithinBars()).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // 52-week high/low
    // -------------------------------------------------------------------------

    @Test
    void fiftyTwoWeekHighLowOverAllBars() {
        // 5 bars (>= minBarsFor52w=5): closing prices 3,1,4,1,5
        var bars = List.of(
                bar(0, 3), bar(1, 1), bar(2, 4), bar(3, 1), bar(4, 5)
        );
        var ind = svc.compute(bars, SMALL_PARAMS);
        assertThat(ind.window52wAvailable()).isTrue();
        // high = max of all highs (each bar: high=close+1, so max close is 5, high=6)
        assertThat(ind.high52w()).isEqualByComparingTo("6");
        // low = min of all lows (each bar: low=close-1, so min close is 1, low=0)
        assertThat(ind.low52w()).isEqualByComparingTo("0");
    }

    @Test
    void fiftyTwoWeekUnavailableWhenTooFewBars() {
        // 4 bars < minBarsFor52w=5
        var ind = svc.compute(rising(4), SMALL_PARAMS);
        assertThat(ind.window52wAvailable()).isFalse();
        // high/low still computed from available bars (not null)
        assertThat(ind.high52w()).isNotNull();
        assertThat(ind.low52w()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Empty / short history
    // -------------------------------------------------------------------------

    /** Mirrors Dracul's emptyHistoryDegradesGracefully. */
    @Test
    void emptyHistoryDegradesGracefully() {
        var ind = svc.compute(List.of(), SMALL_PARAMS);
        assertThat(ind.atrAvailable()).isFalse();
        assertThat(ind.atr()).isNull();
        assertThat(ind.maFastAvailable()).isFalse();
        assertThat(ind.maSlowAvailable()).isFalse();
        assertThat(ind.window52wAvailable()).isFalse();
        assertThat(ind.chandelierBreached()).isFalse();
        assertThat(ind.maCrossState()).isEqualTo("NEUTRAL");
        assertThat(ind.currentClose()).isNull();
        assertThat(ind.crossedWithinBars()).isNull();
    }

    @Test
    void singleBarDegradesGracefully() {
        var ind = svc.compute(List.of(flat(0, 50)), SMALL_PARAMS);
        assertThat(ind.atrAvailable()).isFalse();
        assertThat(ind.currentClose()).isEqualByComparingTo("50");
        assertThat(ind.maCrossState()).isEqualTo("NEUTRAL");
    }

    // -------------------------------------------------------------------------
    // Params validation
    // -------------------------------------------------------------------------

    @Test
    void invalidParamsThrow() {
        assertThatThrownBy(() -> new IndicatorService.Params(0, BigDecimal.ONE, 2, 4, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndicatorService.Params(3, BigDecimal.ONE, 0, 4, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndicatorService.Params(3, BigDecimal.ONE, 4, 4, 5))
                .isInstanceOf(IllegalArgumentException.class); // maSlow not > maFast
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Bar at day i with close=px, high=px+1, low=px-1. */
    private OhlcBar bar(int i, int px) {
        var c = new BigDecimal(px);
        return new OhlcBar(LocalDate.of(2025, 1, 1).plusDays(i),
                c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000);
    }
}
