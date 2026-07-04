package de.visterion.agora.data;

import java.math.BigDecimal;
import java.time.Instant;

/** One intraday OHLCV bar at the requested interval; oldest-first in a list. */
public record IntradayBar(Instant time, BigDecimal open, BigDecimal high, BigDecimal low,
                          BigDecimal close, long volume) {}
