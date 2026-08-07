package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.fetch.edgar.ConceptDatapoint;
import de.visterion.agora.fetch.edgar.EdgarService.ConceptSeries;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "The provider was down" must not read as "this company has no 52-week low".
 *
 * <p>Both cases used to produce byte-identical metrics: {@code GlobalMetricsService} caught every
 * {@link MarketDataException} from the ohlc fetch with {@code /* omit 52w metrics *}{@code /} and
 * left {@code 52WeekLow} simply absent — the same absence a genuinely value-less instrument
 * produces. A consumer (Dracul's lazarus screen counts {@code no52wLow}) could not tell a partial
 * Alpaca outage from a legitimately empty series.
 *
 * <p>Pinned here: an {@code UNAVAILABLE} carries a payload-level {@code 52WeekRange.available=false}
 * marker, a genuine absence (empty series, or the instrument-scoped {@code NOT_FOUND}) does not.
 */
class GlobalMetrics52WeekDegradationTest {

    private static final Instrument SYNA = Instrument.raw("SYNA");

    private static ConceptSeries series(String unit, double value) {
        return new ConceptSeries(unit, List.of(new ConceptDatapoint(
                null, LocalDate.parse("2025-12-31"), BigDecimal.valueOf(value), 2025, "FY", null,
                LocalDate.parse("2025-12-31"))));
    }

    /** Enough concepts that the fundamentals half of the payload is healthy either way. */
    private static FundamentalsRouter healthyRouter() {
        FundamentalsRouter router = mock(FundamentalsRouter.class);
        Map<FundamentalConcept, ConceptSeries> concepts = Map.of(
                FundamentalConcept.NET_INCOME, series("USD", 100),
                FundamentalConcept.TOTAL_ASSETS, series("USD", 1000),
                FundamentalConcept.REVENUE, series("USD", 500),
                FundamentalConcept.TOTAL_LIABILITIES, series("USD", 400));
        when(router.facts(any())).thenReturn(new SourceResult(concepts, AbsenceSemantics.SPARSE));
        return router;
    }

    private static MarketDataService ohlcThrowing(MarketDataException.Kind kind, String message) {
        MarketDataService md = mock(MarketDataService.class);
        when(md.ohlc(anyString(), anyInt())).thenThrow(new MarketDataException(kind, message, null));
        when(md.quote(anyString())).thenThrow(
                new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "quote not part of this test", null));
        return md;
    }

    private JsonNode metricsOf(MarketDataService md) {
        return new GlobalMetricsService(healthyRouter(), md, null).metrics(SYNA).metrics();
    }

    @Test void providerOutageIsMarkedUnavailableRatherThanLookingLikeNoLow() {
        JsonNode m = metricsOf(ohlcThrowing(MarketDataException.Kind.UNAVAILABLE, "alpaca failed: read timeout"));

        assertThat(m.path("52WeekLow").isMissingNode())
                .as("no value can be computed from a failed fetch")
                .isTrue();
        assertThat(m.path("52WeekRange").path("available").asBoolean(true))
                .as("the outage must be visible in the payload, not swallowed")
                .isFalse();
        assertThat(m.path("52WeekRange").path("error").asString(""))
                .contains("alpaca failed");
        assertThat(m.path("roaTTM").isNumber())
                .as("degrading the 52w group must not throw away the healthy fundamentals")
                .isTrue();
    }

    @Test void genuinelyEmptySeriesCarriesNoUnavailableMarker() {
        MarketDataService md = mock(MarketDataService.class);
        when(md.ohlc(anyString(), anyInt())).thenReturn(List.of());
        when(md.quote(anyString())).thenThrow(
                new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "quote not part of this test", null));

        JsonNode m = metricsOf(md);

        assertThat(m.path("52WeekLow").isMissingNode()).isTrue();
        assertThat(m.path("52WeekRange").isMissingNode())
                .as("a genuine absence is not a degradation")
                .isTrue();
    }

    /** NOT_FOUND is instrument-scoped ("no bars exist here") — the same statement-about-the-item
     *  semantics ToolResult.noData encodes at the tool boundary, so it is a genuine absence too. */
    @Test void notFoundIsAGenuineAbsenceNotADegradation() {
        JsonNode m = metricsOf(ohlcThrowing(MarketDataException.Kind.NOT_FOUND, "no ohlc for SYNA"));

        assertThat(m.path("52WeekLow").isMissingNode()).isTrue();
        assertThat(m.path("52WeekRange").isMissingNode()).isTrue();
    }

    /** The whole point: the two cases must not be byte-identical any more. */
    @Test void outageAndGenuineAbsenceAreDistinguishable() {
        MarketDataService healthy = mock(MarketDataService.class);
        when(healthy.ohlc(anyString(), anyInt())).thenReturn(List.of());
        when(healthy.quote(anyString())).thenThrow(
                new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "quote not part of this test", null));

        JsonNode absent = metricsOf(healthy).deepCopy();
        JsonNode outage = metricsOf(ohlcThrowing(MarketDataException.Kind.UNAVAILABLE, "yahoo failed: 503"));

        assertThat(outage).isNotEqualTo(absent);
    }

    /** A successful fetch keeps producing the plain values with no marker. */
    @Test void successfulFetchIsUnchanged() {
        MarketDataService md = mock(MarketDataService.class);
        when(md.ohlc(anyString(), anyInt())).thenReturn(List.of(
                bar("2026-01-02", "10.00"), bar("2026-01-03", "14.50"), bar("2026-01-04", "12.25")));
        when(md.quote(anyString())).thenThrow(
                new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "quote not part of this test", null));

        JsonNode m = metricsOf(md);

        assertThat(m.path("52WeekLow").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(m.path("52WeekHigh").decimalValue()).isEqualByComparingTo("14.50");
        assertThat(m.path("52WeekRange").isMissingNode()).isTrue();
    }

    private static OhlcBar bar(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new OhlcBar(LocalDate.parse(date), c, c, c, c, 1_000L);
    }
}
