package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.DecimalNumFactory;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bridges Agora's OhlcBar list to a ta4j BarSeries (DecimalNum precision) and reads values back. */
public final class Ta4jBars {

    private static final Logger log = LoggerFactory.getLogger(Ta4jBars.class);
    private static final Duration ONE_DAY = Duration.ofDays(1);
    private static final int MAX_LOGGED_DATES = 3;

    private Ta4jBars() {}

    /** Build a DecimalNum-backed daily series from provider OhlcBars. Providers occasionally
     *  return duplicate-date rows (last one wins) or out-of-order rows — de-duplicate and sort
     *  ascending here instead of letting ta4j throw on a non-monotonic end time.
     *
     *  <p>Since ta4j 0.25 a bar whose high is below its open/close/low (or whose low is above
     *  them) is rejected with an IllegalArgumentException. Real provider data contains such rows
     *  (free daily feeds around auctions and exchange holidays), so one bad row would fail the
     *  whole indicator call. Such a bar is repaired here, at the ta4j boundary only: open and
     *  close stay as reported and high/low are widened to the envelope of all four prices. No
     *  row is dropped, so the series stays index-aligned with the provider list. Raw provider
     *  data (e.g. {@code get_ohlc}) is not altered. */
    public static BarSeries toSeries(List<OhlcBar> bars) {
        BarSeries series = new BaseBarSeriesBuilder()
                .withNumFactory(DecimalNumFactory.getInstance())
                .withName("agora")
                .build();
        List<java.time.LocalDate> repaired = new ArrayList<>();
        for (OhlcBar b : dedupAndSort(bars)) {
            BigDecimal high = b.high().max(b.open()).max(b.close()).max(b.low());
            BigDecimal low = b.low().min(b.open()).min(b.close()).min(b.high());
            if (high.compareTo(b.high()) != 0 || low.compareTo(b.low()) != 0) {
                repaired.add(b.date());
            }
            series.barBuilder()
                    .endTime(b.date().atStartOfDay(ZoneOffset.UTC).toInstant())
                    .timePeriod(ONE_DAY)
                    .openPrice(b.open().toPlainString())
                    .highPrice(high.toPlainString())
                    .lowPrice(low.toPlainString())
                    .closePrice(b.close().toPlainString())
                    .volume(Long.toString(b.volume()))
                    .add();
        }
        if (!repaired.isEmpty()) {
            log.warn("widened high/low of {} bar(s) whose OHLC was inconsistent (high/low not "
                    + "covering open/close), e.g. {}", repaired.size(),
                    repaired.subList(0, Math.min(MAX_LOGGED_DATES, repaired.size())));
        }
        return series;
    }

    /** Keeps the last row per date (LinkedHashMap#put overwrites on collision), then sorts
     *  ascending by date — order-independent, so unsorted input is handled the same way.
     *
     *  <p>Public because the indicator tools (package {@code de.visterion.agora.tools}) normalise
     *  the provider list with this exact rule before deciding whether the last bar belongs to a
     *  session that is still running: after de-duplication there is exactly one row per date, so
     *  "the last row" and "the latest date" are the same element in the tool and in the series. */
    public static List<OhlcBar> dedupAndSort(List<OhlcBar> bars) {
        Map<java.time.LocalDate, OhlcBar> byDate = new LinkedHashMap<>();
        for (OhlcBar b : bars) byDate.put(b.date(), b);
        List<OhlcBar> out = new ArrayList<>(byDate.values());
        out.sort(Comparator.comparing(OhlcBar::date));
        return out;
    }

    /** Value of an indicator at the last bar. */
    public static Num last(Indicator<Num> indicator) {
        BarSeries s = indicator.getBarSeries();
        if (s.isEmpty()) throw new IllegalArgumentException("cannot read last value of an empty series");
        return indicator.getValue(s.getEndIndex());
    }

    /** Read a Num as BigDecimal rounded HALF_UP to scale. */
    public static BigDecimal toBd(Num n, int scale) {
        return n.bigDecimalValue().setScale(scale, RoundingMode.HALF_UP);
    }

    /** Convert a BigDecimal to the series' Num type (single point of API coupling). */
    public static Num num(BarSeries series, BigDecimal value) {
        return series.numFactory().numOf(value);
    }
}
