package de.visterion.agora.data;

public class MarketDataException extends RuntimeException {
    /**
     * TOO_LARGE is deliberately distinct from UNAVAILABLE: the source answered correctly, this
     * one document simply exceeds the configured byte cap. Retrying it will fail identically
     * forever, whereas UNAVAILABLE is a transient transport/source condition worth retrying.
     * Conflating the two made an 8 MB merger proxy read as "EDGAR is down" in consumer logs.
     */
    public enum Kind { NOT_FOUND, UNAVAILABLE, RATE_LIMITED, TOO_LARGE }
    private final Kind kind;
    public MarketDataException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }
    public Kind kind() { return kind; }
}
