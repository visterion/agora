package de.visterion.agora.trading;

public class BrokerException extends RuntimeException {
    public enum Kind { UNAVAILABLE, NOT_FOUND }
    private final Kind kind;
    public BrokerException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public Kind kind() { return kind; }
}
