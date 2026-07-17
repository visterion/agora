package de.visterion.agora.data;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared non-US exchange-suffix set, sourced from the single
 * {@code agora.fundamentals.non-us-suffixes} property (see {@code application.yaml}) so
 * market-data routing ({@link MarketDataProvider#canServe(Instrument)}) and fundamentals
 * routing ({@code FundamentalsRouter}, {@code GlobalMetricsRouter}) never drift apart.
 *
 * <p>{@link #DEFAULT_CSV} mirrors the default embedded in {@code application.yaml}'s
 * {@code agora.fundamentals.non-us-suffixes} property; it exists so callers bound via
 * {@code @Value} can reference one compile-time constant instead of re-typing the 21-suffix
 * literal.
 */
public final class NonUsSuffixes {

    /** Mirrors application.yaml: agora.fundamentals.non-us-suffixes default. */
    public static final String DEFAULT_CSV =
            "DE,MI,TO,L,T,HK,PA,AS,SW,AX,ST,CO,OL,HE,MC,BR,LS,VI,IR,NZ";

    public static final Set<String> DEFAULT = parse(DEFAULT_CSV);

    private NonUsSuffixes() {}

    public static Set<String> parse(String csv) {
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * True iff {@code inst} is a non-US instrument: either its display symbol carries a
     * mapped non-US exchange suffix ({@link Instrument#classify(String, Set)} ==
     * {@code SUFFIXED}), or it carries an explicit non-US {@link Instrument#countryCode()}.
     */
    public static boolean isNonUs(Instrument inst, Set<String> nonUsSuffixes) {
        if (Instrument.classify(inst.displaySymbol(), nonUsSuffixes) == Instrument.InputKind.SUFFIXED) {
            return true;
        }
        String cc = inst.countryCode();
        return cc != null && !cc.isBlank() && !cc.equalsIgnoreCase("US");
    }
}
