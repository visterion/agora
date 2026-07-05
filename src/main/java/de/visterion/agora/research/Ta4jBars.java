package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.DecimalNumFactory;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

/** Bridges Agora's OhlcBar list to a ta4j BarSeries (DecimalNum precision) and reads values back. */
public final class Ta4jBars {

    private static final Duration ONE_DAY = Duration.ofDays(1);

    private Ta4jBars() {}

    /** Build a DecimalNum-backed daily series from oldest-first OhlcBars. */
    public static BarSeries toSeries(List<OhlcBar> bars) {
        BarSeries series = new BaseBarSeriesBuilder()
                .withNumFactory(DecimalNumFactory.getInstance())
                .withName("agora")
                .build();
        for (OhlcBar b : bars) {
            series.barBuilder()
                    .endTime(b.date().atStartOfDay(ZoneOffset.UTC).toInstant())
                    .timePeriod(ONE_DAY)
                    .openPrice(b.open().toPlainString())
                    .highPrice(b.high().toPlainString())
                    .lowPrice(b.low().toPlainString())
                    .closePrice(b.close().toPlainString())
                    .volume(Long.toString(b.volume()))
                    .add();
        }
        return series;
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
