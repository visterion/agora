package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;
import de.visterion.agora.data.InstrumentResolver;
import de.visterion.agora.fetch.finnhub.Fundamentals;
import de.visterion.agora.fetch.finnhub.FundamentalsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Routes {@code get_fundamentals} lookups: US -> Finnhub (unchanged), non-US -> {@link GlobalMetricsService},
 *  gated by {@code agora.fundamentals.global-metrics-enabled} (default off). With the flag off, every symbol
 *  -- including non-US -- goes to Finnhub, byte-identical to pre-SP2a behavior. */
@Component
public class GlobalMetricsRouter {
    private final FundamentalsService finnhub;
    private final GlobalMetricsService global;
    private final InstrumentResolver resolver;
    private final boolean globalMetricsEnabled;
    private final Set<String> nonUsSuffixes;

    @Autowired
    public GlobalMetricsRouter(FundamentalsService finnhub, GlobalMetricsService global, InstrumentResolver resolver,
                               @Value("${agora.fundamentals.global-metrics-enabled:false}") boolean globalMetricsEnabled,
                               @Value("${agora.fundamentals.non-us-suffixes:DE,MI,TO,L,T,HK,PA,AS,SW,AX,ST,CO,OL,HE,MC,BR,LS,VI,IR,NZ}") String suffixesCsv) {
        this(finnhub, global, resolver, globalMetricsEnabled, Arrays.stream(suffixesCsv.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet()));
    }

    GlobalMetricsRouter(FundamentalsService finnhub, GlobalMetricsService global, InstrumentResolver resolver,
                        boolean globalMetricsEnabled, Set<String> nonUsSuffixes) {
        this.finnhub = finnhub; this.global = global; this.resolver = resolver;
        this.globalMetricsEnabled = globalMetricsEnabled; this.nonUsSuffixes = nonUsSuffixes;
    }

    public Fundamentals fundamentals(String symbol) {
        Instrument inst = resolver.resolve(symbol);
        if (globalMetricsEnabled && Instrument.classify(symbol, nonUsSuffixes) == Instrument.InputKind.SUFFIXED) {
            return global.metrics(inst);
        }
        return finnhub.fundamentals(symbol);
    }
}
