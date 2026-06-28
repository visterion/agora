package de.visterion.agora.research;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

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
}
