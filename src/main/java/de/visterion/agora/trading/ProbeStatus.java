package de.visterion.agora.trading;

import java.time.Instant;

/** Result of the startup liveness probe. state: "unknown" | "ok" | "unreachable" | "pending". */
public record ProbeStatus(String state, Instant probedAt, String detail) {
    public static ProbeStatus unknown() { return new ProbeStatus("unknown", null, null); }
    public static ProbeStatus ok(Instant at) { return new ProbeStatus("ok", at, null); }
    public static ProbeStatus unreachable(Instant at, String detail) { return new ProbeStatus("unreachable", at, detail); }
    public static ProbeStatus pending(Instant at, String detail) { return new ProbeStatus("pending", at, detail); }
}
