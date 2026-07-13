package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class FundamentalsRouter {
    private final EdgarFundamentalsSource edgar;
    private final YahooTimeseriesFundamentalsSource yahoo;
    private final Set<String> nonUsSuffixes;

    @Autowired
    public FundamentalsRouter(EdgarFundamentalsSource edgar, YahooTimeseriesFundamentalsSource yahoo,
                              @Value("${agora.fundamentals.non-us-suffixes:DE,MI,TO,L,T,HK,PA,AS,SW,AX,ST,CO,OL,HE,MC,BR,LS,VI,IR,NZ}") String suffixesCsv) {
        this(edgar, yahoo, Arrays.stream(suffixesCsv.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet()));
    }
    FundamentalsRouter(EdgarFundamentalsSource edgar, YahooTimeseriesFundamentalsSource yahoo, Set<String> nonUsSuffixes) {
        this.edgar = edgar; this.yahoo = yahoo; this.nonUsSuffixes = nonUsSuffixes;
    }

    public SourceResult facts(Instrument inst) {
        String input = inst.displaySymbol();
        if (Instrument.isIsin(input)) {
            return input.regionMatches(true, 0, "US", 0, 2) ? edgar.facts(inst) : yahoo.facts(inst);
        }
        return Instrument.classify(input, nonUsSuffixes) == Instrument.InputKind.SUFFIXED
                ? yahoo.facts(inst) : edgar.facts(inst);
    }
}
