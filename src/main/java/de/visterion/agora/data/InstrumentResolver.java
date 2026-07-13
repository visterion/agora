package de.visterion.agora.data;

/** Resolves a caller string (US ticker / Yahoo-suffix / ISIN) into a canonical Instrument.
 *  Implementations MUST NOT throw — every failure returns Instrument.raw(input). */
public interface InstrumentResolver {
    Instrument resolve(String input);
}
