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
        assertThat(r.minBars()).isEqualTo(15);
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
    void minBarsPropagatesThroughChain() {
        var series = Ta4jBars.toSeries(rising(30));
        var r = resolver.resolve(json("{\"name\":\"sma\",\"params\":{\"period\":2},\"of\":\"rsi\"}"), series);
        // max(own 3, sub 15) = 15
        assertThat(r.minBars()).isEqualTo(15);
    }
}
