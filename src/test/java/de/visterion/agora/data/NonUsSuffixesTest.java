package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NonUsSuffixesTest {

    @Test void suffixedSymbolsAreNonUs() {
        Set<String> s = NonUsSuffixes.parse(NonUsSuffixes.DEFAULT_CSV);
        assertThat(NonUsSuffixes.isNonUs("SAP.DE", s)).isTrue();
        assertThat(NonUsSuffixes.isNonUs("9984.T", s)).isTrue();
        assertThat(NonUsSuffixes.isNonUs("0700.HK", s)).isTrue();
    }

    @Test void usFormatSymbolsAreNotNonUs() {
        Set<String> s = NonUsSuffixes.parse(NonUsSuffixes.DEFAULT_CSV);
        assertThat(NonUsSuffixes.isNonUs("AAPL", s)).isFalse();
        assertThat(NonUsSuffixes.isNonUs("BYDDY", s)).isFalse(); // US-format ADR — not skipped
    }
}
