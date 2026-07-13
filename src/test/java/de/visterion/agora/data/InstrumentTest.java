package de.visterion.agora.data;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class InstrumentTest {
    static final Set<String> SUFFIXES = Set.of("DE", "MI", "TO");

    @Test void rawFactoryKeepsInputAndIsUnresolved() {
        Instrument i = Instrument.raw("AAPL");
        assertThat(i.rawInput()).isEqualTo("AAPL");
        assertThat(i.displaySymbol()).isEqualTo("AAPL");
        assertThat(i.resolved()).isFalse();
        assertThat(i.uic()).isNull();
        assertThat(i.isin()).isNull();
    }

    @Test void validIsinIsRecognised() {           // DE0007164600 = SAP, valid check digit
        assertThat(Instrument.isIsin("DE0007164600")).isTrue();
    }

    @Test void isinWithBadCheckDigitIsNotAnIsin() {
        assertThat(Instrument.isIsin("DE0007164601")).isFalse();
    }

    @Test void twelveCharTickerShapedButInvalidIsNotIsin() {
        assertThat(Instrument.isIsin("ABCDEFGHIJK1")).isFalse();
    }

    @Test void classifyRoutesInputs() {
        assertThat(Instrument.classify("AAPL", SUFFIXES)).isEqualTo(Instrument.InputKind.US_OR_UNMAPPED);
        assertThat(Instrument.classify("BRK.B", SUFFIXES)).isEqualTo(Instrument.InputKind.US_OR_UNMAPPED);
        assertThat(Instrument.classify("XYZ.OL", SUFFIXES)).isEqualTo(Instrument.InputKind.US_OR_UNMAPPED);
        assertThat(Instrument.classify("SAP.DE", SUFFIXES)).isEqualTo(Instrument.InputKind.SUFFIXED);
        assertThat(Instrument.classify("DE0007164600", SUFFIXES)).isEqualTo(Instrument.InputKind.ISIN);
    }
}
