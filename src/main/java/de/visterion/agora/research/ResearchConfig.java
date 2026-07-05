package de.visterion.agora.research;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wires the default {@link IndicatorService.Params} from {@code agora.research.*}
 * properties and exposes an {@link IndicatorService} bean.
 *
 * <p>Only {@code get_r_framework} injects this bean's {@link ResearchDefaults} to seed its
 * call-time defaults; indicator params for {@code get_indicators} live in the indicator
 * catalog instead.</p>
 */
@Configuration
public class ResearchConfig {

    @Bean
    public IndicatorService indicatorService(
            @Value("${agora.research.atr-period:22}") int atrPeriod,
            @Value("${agora.research.atr-multiple:3.0}") BigDecimal atrMultiple,
            @Value("${agora.research.ma-fast:50}") int maFast,
            @Value("${agora.research.ma-slow:200}") int maSlow,
            @Value("${agora.research.min-bars-52w:250}") int minBarsFor52w) {
        return new IndicatorService(new IndicatorService.Params(atrPeriod, atrMultiple, maFast, maSlow, minBarsFor52w));
    }

    @Bean
    public ResearchDefaults researchDefaults(
            @Value("${agora.research.r-atr-multiple:3.0}") BigDecimal rAtrMultiple,
            @Value("${agora.research.r-multiples:1,2,3}") List<BigDecimal> rMultiples,
            @Value("${agora.research.fetch-days:260}") int fetchDays) {
        return new ResearchDefaults(rAtrMultiple, rMultiples, fetchDays);
    }
}
