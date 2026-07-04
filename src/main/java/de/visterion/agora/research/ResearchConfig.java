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
 * <p>The four research tools inject this bean and may override individual params
 * per-call by constructing a new {@code Params} from the injected defaults.</p>
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
            @Value("${agora.research.rsi-period:14}") int rsiPeriod,
            @Value("${agora.research.macd-fast:12}") int macdFast,
            @Value("${agora.research.macd-slow:26}") int macdSlow,
            @Value("${agora.research.macd-signal:9}") int macdSignal,
            @Value("${agora.research.bollinger-period:20}") int bollingerPeriod,
            @Value("${agora.research.bollinger-k:2}") BigDecimal bollingerK,
            @Value("${agora.research.stochastic-k:14}") int stochasticK,
            @Value("${agora.research.stochastic-d:3}") int stochasticD,
            @Value("${agora.research.adx-period:14}") int adxPeriod,
            @Value("${agora.research.cci-period:20}") int cciPeriod,
            @Value("${agora.research.williams-period:14}") int williamsPeriod,
            @Value("${agora.research.r-atr-multiple:3.0}") BigDecimal rAtrMultiple,
            @Value("${agora.research.r-multiples:1,2,3}") List<BigDecimal> rMultiples,
            @Value("${agora.research.fetch-days:260}") int fetchDays) {
        return new ResearchDefaults(rsiPeriod, macdFast, macdSlow, macdSignal,
                bollingerPeriod, bollingerK, stochasticK, stochasticD,
                adxPeriod, cciPeriod, williamsPeriod, rAtrMultiple, rMultiples, fetchDays);
    }
}
