package de.visterion.agora.data;

import java.util.Locale;
import java.util.Set;

/** Canonical instrument identity. Only Saxo consumes the identity fields; pass-through
 *  providers use {@link #displaySymbol()} (== rawInput). See the design spec. */
public record Instrument(
        String rawInput, String displaySymbol, String isin, String mic, String exchangeId,
        String currencyCode, Long uic, String countryCode, String assetType, boolean resolved,
        double priceToContractFactor) {

    public static Instrument raw(String input) {
        return new Instrument(input, input, null, null, null, null, null, null, "Stock", false, 1.0);
    }

    public enum InputKind { US_OR_UNMAPPED, SUFFIXED, ISIN }

    public static InputKind classify(String input, Set<String> mappedSuffixes) {
        if (isIsin(input)) return InputKind.ISIN;
        int dot = input.lastIndexOf('.');
        if (dot > 0) {
            String suffix = input.substring(dot + 1).toUpperCase(Locale.ROOT);
            if (mappedSuffixes.contains(suffix)) return InputKind.SUFFIXED;
        }
        return InputKind.US_OR_UNMAPPED;
    }

    /** ISO 6166 shape [A-Z]{2}[A-Z0-9]{9}[0-9] AND a valid mod-10 (Luhn-for-ISIN) check digit. */
    public static boolean isIsin(String s) {
        if (s == null || s.length() != 12) return false;
        for (int i = 0; i < 12; i++) {
            char c = s.charAt(i);
            boolean ok = (i < 2) ? (c >= 'A' && c <= 'Z')
                    : (i < 11) ? ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))
                    : (c >= '0' && c <= '9');
            if (!ok) return false;
        }
        // Expand letters to two digits (A=10..Z=35), then mod-10 double-every-second-from-right.
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            char c = s.charAt(i);
            digits.append(Character.isLetter(c) ? Integer.toString(c - 'A' + 10) : Character.toString(c));
        }
        int sum = 0; boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d; dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
