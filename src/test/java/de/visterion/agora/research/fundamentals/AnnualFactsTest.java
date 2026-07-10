package de.visterion.agora.research.fundamentals;

import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AnnualFactsTest {
    private static ConceptDatapoint dur(String start, String end, long v) {
        return new ConceptDatapoint(LocalDate.parse(start), LocalDate.parse(end), BigDecimal.valueOf(v), null, "FY", "10-K", null);
    }
    private static ConceptDatapoint inst(String end, long v) {
        return new ConceptDatapoint(null, LocalDate.parse(end), BigDecimal.valueOf(v), null, "FY", "10-K", null);
    }
    private static ConceptDatapoint instQ(String end, long v, String fp) {
        return new ConceptDatapoint(null, LocalDate.parse(end), BigDecimal.valueOf(v), null, fp, "10-Q", null);
    }

    @Test void picksTwoLatestAnnualDurations() {
        var s = new ConceptSeries("USD", List.of(
            dur("2021-01-01","2021-12-31",100),
            dur("2022-01-01","2022-12-31",120),
            dur("2023-01-01","2023-12-31",150),
            dur("2023-07-01","2023-09-30",40))); // quarterly, ignored
        var a = AnnualFacts.of(s);
        assertThat(a.available()).isTrue();
        assertThat(a.hasCurrent()).isTrue();
        assertThat(a.current()).isEqualByComparingTo("150");
        assertThat(a.prior()).isEqualByComparingTo("120");
    }

    @Test void picksTwoLatestInstants() {
        var s = new ConceptSeries("USD", List.of(inst("2022-12-31",500), inst("2023-12-31",600)));
        var a = AnnualFacts.ofInstant(s);
        assertThat(a.current()).isEqualByComparingTo("600");
        assertThat(a.prior()).isEqualByComparingTo("500");
    }

    @Test void unavailableWhenFewerThanTwoButHasCurrent() {
        var a = AnnualFacts.of(new ConceptSeries("USD", List.of(dur("2023-01-01","2023-12-31",150))));
        assertThat(a.available()).isFalse();
        assertThat(a.hasCurrent()).isTrue();
        assertThat(a.current()).isEqualByComparingTo("150");
    }

    @Test void emptySeriesHasNoCurrent() {
        var a = AnnualFacts.of(new ConceptSeries(null, List.of()));
        assertThat(a.hasCurrent()).isFalse();
        assertThat(a.available()).isFalse();
    }

    @Test void ofInstantIgnoresQuarterlySnapshots() {
        var s = new ConceptSeries("USD", List.of(
            inst("2022-12-31", 500),
            instQ("2023-03-31", 510, "Q1"),
            instQ("2023-06-30", 520, "Q2"),
            instQ("2023-09-30", 530, "Q3"),
            inst("2023-12-31", 600)));
        var a = AnnualFacts.ofInstant(s);
        assertThat(a.current()).isEqualByComparingTo("600");
        assertThat(a.prior()).isEqualByComparingTo("500");
    }

    private static ConceptDatapoint durFiled(String start, String end, long v, String filed) {
        return new ConceptDatapoint(LocalDate.parse(start), LocalDate.parse(end), BigDecimal.valueOf(v), null, "FY", "10-K", LocalDate.parse(filed));
    }

    @Test void dedupTieBreaksByLatestFiledForSamePeriodEnd() {
        // Two facts sharing the same period-end (a restatement): the earlier-filed original
        // is listed FIRST to prove the winner is chosen by latest `filed`, not by list order.
        var s = new ConceptSeries("USD", List.of(
            durFiled("2022-01-01", "2022-12-31", 100, "2023-02-01"),
            durFiled("2023-01-01", "2023-12-31", 150, "2024-02-01"),
            durFiled("2023-01-01", "2023-12-31", 155, "2024-06-01"))); // restated 2023, filed later, higher value
        var a = AnnualFacts.of(s);
        assertThat(a.current()).isEqualByComparingTo("155");
        assertThat(a.prior()).isEqualByComparingTo("100");
    }

    @Test void ofInstantWithoutFyTagsIsUnavailable() {
        var s = new ConceptSeries("USD", List.of(
            instQ("2023-03-31", 510, "Q1"),
            instQ("2023-06-30", 520, null)));
        var a = AnnualFacts.ofInstant(s);
        assertThat(a.hasCurrent()).isFalse();
        assertThat(a.available()).isFalse();
    }
}
