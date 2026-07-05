package de.visterion.agora.research;

import org.junit.jupiter.api.Test;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YamlIndicatorCatalogTest {

    private static InputStream yaml(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private List<de.visterion.agora.data.OhlcBar> rising(int n) {
        var bars = new ArrayList<de.visterion.agora.data.OhlcBar>();
        for (int i = 0; i < n; i++) {
            var c = new BigDecimal(100 + i);
            bars.add(new de.visterion.agora.data.OhlcBar(LocalDate.of(2025, 1, 1).plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000));
        }
        return bars;
    }

    @Test
    void loadsInputsOneEntryAndComputes() {
        var defs = YamlIndicatorCatalog.load(yaml("""
                indicators:
                  rsi:
                    class: org.ta4j.core.indicators.RSIIndicator
                    description: "Relative Strength Index"
                    inputs: 1
                    params:
                      - { name: period, type: int, default: 14, min: 1, max: 500 }
                """));
        assertThat(defs).hasSize(1);
        var def = defs.getFirst();
        assertThat(def.name()).isEqualTo("rsi");
        assertThat(def.inputs()).isEqualTo(1);
        assertThat(def.params().getFirst().defaultValue()).isEqualByComparingTo("14");
        assertThat(def.minBars().applyAsInt(ResolvedParams.defaults(def.params()))).isEqualTo(15);

        var series = Ta4jBars.toSeries(rising(30));
        @SuppressWarnings("unchecked")
        Indicator<Num>[] in = new Indicator[]{ new ClosePriceIndicator(series) };
        var outs = def.factory().create(series, in, ResolvedParams.defaults(def.params()));
        // strictly rising closes → RSI = 100 (same as the old get_rsi contract)
        assertThat(Ta4jBars.toBd(outs.get("value").getValue(series.getEndIndex()), 4))
                .isEqualByComparingTo("100");
    }

    @Test
    void loadsInputsZeroEntry() {
        var defs = YamlIndicatorCatalog.load(yaml("""
                indicators:
                  cci:
                    class: org.ta4j.core.indicators.CCIIndicator
                    inputs: 0
                    params:
                      - { name: period, default: 20 }
                """));
        assertThat(defs).hasSize(1);
        var series = Ta4jBars.toSeries(rising(30));
        @SuppressWarnings("unchecked")
        Indicator<Num>[] none = new Indicator[0];
        var outs = defs.getFirst().factory().create(series, none,
                ResolvedParams.defaults(defs.getFirst().params()));
        assertThat(outs.get("value").getValue(series.getEndIndex())).isNotNull();
    }

    @Test
    void rejectsNonWhitelistedClass() {
        var defs = YamlIndicatorCatalog.load(yaml("""
                indicators:
                  evil:
                    class: java.lang.ProcessBuilder
                    inputs: 0
                """));
        assertThat(defs).isEmpty();
    }

    @Test
    void skipsEntryWithoutMatchingConstructor() {
        // RSIIndicator has no (BarSeries, int) constructor → entry skipped, no throw
        var defs = YamlIndicatorCatalog.load(yaml("""
                indicators:
                  broken:
                    class: org.ta4j.core.indicators.RSIIndicator
                    inputs: 0
                    params:
                      - { name: period, default: 14 }
                """));
        assertThat(defs).isEmpty();
    }

    @Test
    void skipsDecimalParams() {
        var defs = YamlIndicatorCatalog.load(yaml("""
                indicators:
                  bad:
                    class: org.ta4j.core.indicators.RSIIndicator
                    inputs: 1
                    params:
                      - { name: period, type: decimal, default: 14.5 }
                """));
        assertThat(defs).isEmpty();
    }

    @Test
    void emptyOrGarbageYamlYieldsEmptyList() {
        assertThat(YamlIndicatorCatalog.load(yaml(""))).isEmpty();
        assertThat(YamlIndicatorCatalog.load(yaml("not: relevant"))).isEmpty();
    }

    @Test
    void builtinCatalogFileLoadsAllSeventeenEntries() {
        try (InputStream in = getClass().getResourceAsStream("/indicators-catalog.yaml")) {
            var defs = YamlIndicatorCatalog.load(in);
            assertThat(defs).extracting(IndicatorDef::name).containsExactlyInAnyOrder(
                    "rsi", "sma", "ema", "wma", "kama", "roc", "ppo", "dpo",
                    "stddev", "mean_deviation", "highest", "lowest",
                    "cci", "adx", "williams_r", "obv", "parabolic_sar");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
