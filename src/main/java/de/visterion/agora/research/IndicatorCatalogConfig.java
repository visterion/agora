package de.visterion.agora.research;

import de.visterion.agora.data.OhlcBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Assembles the IndicatorRegistry bean: built-in Java composites, then the classpath
 *  YAML catalog, then an optional operator file (overrides on name collision). Every
 *  entry is boot-validated against a synthetic series; broken entries are logged and
 *  removed — the server always starts. */
@Configuration
public class IndicatorCatalogConfig {

    private static final Logger log = LoggerFactory.getLogger(IndicatorCatalogConfig.class);

    @Bean
    public IndicatorRegistry indicatorRegistry(
            @Value("${agora.research.indicators-file:}") String operatorFile) {
        IndicatorRegistry registry = new IndicatorRegistry();
        BuiltinIndicators.defs().forEach(registry::register);

        try (InputStream in = getClass().getResourceAsStream("/indicators-catalog.yaml")) {
            if (in != null) YamlIndicatorCatalog.load(in).forEach(registry::register);
            else log.error("indicator catalog: /indicators-catalog.yaml missing from classpath");
        } catch (Exception e) {
            log.error("indicator catalog: failed to load built-in catalog: {}", e.toString());
        }

        if (operatorFile != null && !operatorFile.isBlank()) {
            try (InputStream in = new FileInputStream(operatorFile)) {
                YamlIndicatorCatalog.load(in).forEach(registry::register);
            } catch (Exception e) {
                log.error("indicator catalog: failed to load operator file {}: {}", operatorFile, e.toString());
            }
        }

        validate(registry);
        log.info("indicator catalog: {} entries active", registry.all().size());
        return registry;
    }

    /** Instantiate each entry with default params against a synthetic series and read
     *  the last value. Broken entries are removed (log + skip). */
    static void validate(IndicatorRegistry registry) {
        BarSeries series = syntheticSeries(300);
        Indicator<Num> close = new ClosePriceIndicator(series);
        for (IndicatorDef def : registry.all()) {
            try {
                ResolvedParams p = ResolvedParams.defaults(def.params());
                @SuppressWarnings("unchecked")
                Indicator<Num>[] inputs = def.inputs() == 1
                        ? new Indicator[]{close} : new Indicator[0];
                Map<String, Indicator<Num>> outs = def.factory().create(series, inputs, p);
                for (Map.Entry<String, Indicator<Num>> e : outs.entrySet()) {
                    e.getValue().getValue(series.getEndIndex());
                }
                if (!outs.keySet().containsAll(def.outputs())) {
                    throw new IllegalStateException("factory outputs " + outs.keySet()
                            + " do not match declared " + def.outputs());
                }
            } catch (Throwable t) {
                log.error("indicator '{}' failed boot validation and was removed: {}",
                        def.name(), t.toString());
                registry.remove(def.name());
            }
        }
    }

    private static BarSeries syntheticSeries(int n) {
        List<OhlcBar> bars = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BigDecimal c = new BigDecimal(100 + i);
            bars.add(new OhlcBar(LocalDate.of(2020, 1, 1).plusDays(i),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 1000L));
        }
        return Ta4jBars.toSeries(bars);
    }
}
