package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndicatorExpressionResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static IndicatorRegistry registry() {
        var reg = new IndicatorRegistry();
        BuiltinIndicators.defs().forEach(reg::register);
        try (InputStream in = IndicatorExpressionResolverTest.class
                .getResourceAsStream("/indicators-catalog.yaml")) {
            YamlIndicatorCatalog.load(in).forEach(reg::register);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return reg;
    }

    private final IndicatorExpressionResolver resolver = new IndicatorExpressionResolver(registry());

    private List<OhlcBar> rising(int n) {
        var bars = new ArrayList<OhlcBar>();
        for (int i = 0; i < n; i++) {
            var c = new BigDecimal(i + 1);
            bars.add(new OhlcBar(LocalDate.of(2025, 1, 1).plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000));
        }
        return bars;
    }

    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test
    void stringSpecUsesDefaultsAndCloseInput() {
        var series = Ta4jBars.toSeries(rising(30));
        var r = resolver.resolve(json("\"rsi\""), series);
        assertThat(r.label()).isEqualTo("rsi");
        assertThat(r.def().name()).isEqualTo("rsi");
        assertThat(r.minBars()).isEqualTo(1 + 4 * 14); // H3: recursive -> convergence-safe minBars
        assertThat(Ta4jBars.toBd(r.outputs().get("value").getValue(series.getEndIndex()), 4))
                .isEqualByComparingTo("100");
    }

    @Test
    void paramsOverrideDefaults() {
        var series = Ta4jBars.toSeries(rising(5));
        // sma(period=2) on closes 1..5 → (4+5)/2 = 4.5
        var r = resolver.resolve(json("{\"name\":\"sma\",\"params\":{\"period\":2}}"), series);
        assertThat(Ta4jBars.toBd(r.outputs().get("value").getValue(series.getEndIndex()), 4))
                .isEqualByComparingTo("4.5");
        assertThat(r.minBars()).isEqualTo(3);
    }

    @Test
    void nestedCompositionHandComputed() {
        var series = Ta4jBars.toSeries(rising(5));
        // sma(2) of sma(2) of close on 1..5: inner @4 = 4.5, @3 = 3.5 → outer = 4
        var r = resolver.resolve(json("""
                {"name":"sma","params":{"period":2},
                 "of":{"name":"sma","params":{"period":2}}}"""), series);
        assertThat(r.label()).isEqualTo("sma(sma)");
        assertThat(Ta4jBars.toBd(r.outputs().get("value").getValue(series.getEndIndex()), 4))
                .isEqualByComparingTo("4");
    }

    @Test
    void chainEquivalentToDirectTa4jComposition() {
        var series = Ta4jBars.toSeries(rising(60));
        var r = resolver.resolve(json("""
                {"name":"sma","params":{"period":5},"of":{"name":"rsi"}}"""), series);
        var expected = new SMAIndicator(
                new RSIIndicator(new ClosePriceIndicator(series), 14), 5);
        assertThat(Ta4jBars.toBd(r.outputs().get("value").getValue(series.getEndIndex()), 4))
                .isEqualByComparingTo(Ta4jBars.toBd(expected.getValue(series.getEndIndex()), 4));
    }

    @Test
    void priceSourceAsInput() {
        var series = Ta4jBars.toSeries(rising(5));
        // highs are close+1 → sma(2) of high = 5.5
        var r = resolver.resolve(json("{\"name\":\"sma\",\"params\":{\"period\":2},\"of\":\"high\"}"), series);
        assertThat(r.label()).isEqualTo("sma(high)");
        assertThat(Ta4jBars.toBd(r.outputs().get("value").getValue(series.getEndIndex()), 4))
                .isEqualByComparingTo("5.5");
    }

    @Test
    void ofStringCanBeAnIndicatorName() {
        var series = Ta4jBars.toSeries(rising(60));
        var r = resolver.resolve(json("{\"name\":\"sma\",\"params\":{\"period\":5},\"of\":\"rsi\"}"), series);
        assertThat(r.label()).isEqualTo("sma(rsi)");
    }

    @Test
    void explicitLabelWins() {
        var series = Ta4jBars.toSeries(rising(30));
        var r = resolver.resolve(json("{\"name\":\"rsi\",\"params\":{\"period\":7},\"label\":\"rsi7\"}"), series);
        assertThat(r.label()).isEqualTo("rsi7");
    }

    @Test
    void errorCases() {
        var series = Ta4jBars.toSeries(rising(30));
        assertThatThrownBy(() -> resolver.resolve(json("\"rsii\""), series))
                .isInstanceOf(IndicatorExpressionResolver.SpecException.class)
                .hasMessageContaining("unknown indicator 'rsii'");
        assertThatThrownBy(() -> resolver.resolve(json("{\"name\":\"rsi\",\"params\":{\"bogus\":1}}"), series))
                .hasMessageContaining("unknown param");
        assertThatThrownBy(() -> resolver.resolve(json("{\"name\":\"rsi\",\"params\":{\"period\":0}}"), series))
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> resolver.resolve(json("{\"name\":\"atr\",\"of\":\"close\"}"), series))
                .hasMessageContaining("does not accept 'of'");
        assertThatThrownBy(() -> resolver.resolve(json("{\"name\":\"sma\",\"of\":{\"name\":\"macd\"}}"), series))
                .hasMessageContaining("multiple outputs");
        assertThatThrownBy(() -> resolver.resolve(json("{\"params\":{}}"), series))
                .hasMessageContaining("needs a name");
        assertThatThrownBy(() -> resolver.resolve(json("42"), series))
                .hasMessageContaining("string or an object");
        assertThatThrownBy(() -> resolver.resolve(json("{\"name\":\"sma\",\"of\":\"nope\"}"), series))
                .hasMessageContaining("unknown indicator 'nope'");
    }

    @Test
    void depthLimit() {
        var series = Ta4jBars.toSeries(rising(30));
        // depth 6: sma(sma(sma(sma(sma(sma(close)))))) → too deep
        String spec = "\"sma\"";
        for (int i = 0; i < 6; i++) {
            spec = "{\"name\":\"sma\",\"params\":{\"period\":2},\"of\":" + spec + "}";
        }
        String deep = spec;
        assertThatThrownBy(() -> resolver.resolve(json(deep), series))
                .hasMessageContaining("too deep");
    }

    @Test
    void nonCoercibleIntParamIsRejected() {
        var series = Ta4jBars.toSeries(rising(30));
        assertThatThrownBy(() -> resolver.resolve(json("{\"name\":\"rsi\",\"params\":{\"period\":\"abc\"}}"), series))
                .isInstanceOf(IndicatorExpressionResolver.SpecException.class)
                .hasMessageContaining("must be an integer");
    }

    @Test
    void decimalForIntParamIsRejectedNotTruncated() {
        var series = Ta4jBars.toSeries(rising(30));
        assertThatThrownBy(() -> resolver.resolve(json("{\"name\":\"rsi\",\"params\":{\"period\":2.7}}"), series))
                .isInstanceOf(IndicatorExpressionResolver.SpecException.class)
                .hasMessageContaining("must be an integer");
    }

    @Test
    void decimalParamHappyPathAndTypeCheck() {
        var series = Ta4jBars.toSeries(rising(30));
        var r = resolver.resolve(json("{\"name\":\"bollinger\",\"params\":{\"k\":2.5}}"), series);
        assertThat(r.outputs()).isNotEmpty();

        assertThatThrownBy(() -> resolver.resolve(json("{\"name\":\"bollinger\",\"params\":{\"k\":true}}"), series))
                .isInstanceOf(IndicatorExpressionResolver.SpecException.class)
                .hasMessageContaining("must be a number");
    }

    @Test
    void minBarsPropagatesThroughChainAdditively() {
        // research low (c): composed minBars must be additive (inner + outerWindow - 1), not max —
        // the outer indicator needs its own full window of *stable* inner values, not just one.
        var series = Ta4jBars.toSeries(rising(90));
        var r = resolver.resolve(json("{\"name\":\"sma\",\"params\":{\"period\":2},\"of\":\"rsi\"}"), series);
        // rsi(14) is now warmup:recursive -> minBars = 1+4*14 = 57. sma(period=2) alone -> 1+2 = 3.
        // additive: subMinBars + ownMinBars - 2 = 57 + 3 - 2 = 58
        int rsiMinBars = 1 + 4 * 14;
        assertThat(r.minBars()).isEqualTo(rsiMinBars + 3 - 2);
    }

    @Test
    void composedMinBarsMatchesBriefWorkedExample() {
        // sma(10) of rsi(14) -> minBars == rsiStable + 10 - 1 (rsiStable=57, sma(10) alone minBars=11)
        var series = Ta4jBars.toSeries(rising(90));
        var r = resolver.resolve(json("{\"name\":\"sma\",\"params\":{\"period\":10},\"of\":\"rsi\"}"), series);
        int rsiMinBars = 1 + 4 * 14;
        assertThat(r.minBars()).isEqualTo(rsiMinBars + 10 - 1);
    }

    @Test
    void nonComposedMinBarsUnchanged() {
        var series = Ta4jBars.toSeries(rising(30));
        var r = resolver.resolve(json("\"sma\""), series);
        assertThat(r.minBars()).isEqualTo(21); // 1 + period(20), unaffected (no 'of' composition)
    }

    // -------------------------------------------------------------------------
    // Task 8 review finding 2: additive composition must not under-count when the OUTER
    // indicator's minBars does not follow the "1 + rawWindow" convention (bollinger, macd,
    // ma_cross). Correct invariant: total = subMinBars - 1 + outerRawWindow.
    // -------------------------------------------------------------------------

    @Test
    void bollingerOfRsiComposedMinBarsIsExactNotUnderCounted() {
        var series = Ta4jBars.toSeries(rising(120));
        var r = resolver.resolve(json("{\"name\":\"bollinger\",\"of\":\"rsi\"}"), series);
        int rsiMinBars = 1 + 4 * 14;   // subMinBars
        int bollingerRawWindow = 20;   // default period; bollinger's minBars IS the raw window
        int expected = rsiMinBars - 1 + bollingerRawWindow;
        assertThat(r.minBars()).isEqualTo(expected).isGreaterThanOrEqualTo(expected);
    }

    @Test
    void macdOfRsiComposedMinBarsIsExactNotUnderCounted() {
        var series = Ta4jBars.toSeries(rising(250));
        var r = resolver.resolve(json("{\"name\":\"macd\",\"of\":\"rsi\"}"), series);
        int rsiMinBars = 1 + 4 * 14;                 // subMinBars
        int macdRawWindow = 4 * (26 + 9);            // macd's own minBars is 1 + 4*(slow+signal)
        int expected = rsiMinBars - 1 + macdRawWindow;
        assertThat(r.minBars()).isEqualTo(expected).isGreaterThanOrEqualTo(expected);
    }

    @Test
    void maCrossOfRsiComposedMinBarsIsExactNotUnderCounted() {
        var series = Ta4jBars.toSeries(rising(300));
        var r = resolver.resolve(json("{\"name\":\"ma_cross\",\"of\":\"rsi\"}"), series);
        int rsiMinBars = 1 + 4 * 14;   // subMinBars
        int maCrossRawWindow = 200;    // default slow; ma_cross's minBars IS the raw window
        int expected = rsiMinBars - 1 + maCrossRawWindow;
        assertThat(r.minBars()).isEqualTo(expected).isGreaterThanOrEqualTo(expected);
    }
}
