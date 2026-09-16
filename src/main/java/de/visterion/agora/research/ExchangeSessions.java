package de.visterion.agora.research;

import de.visterion.agora.data.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a symbol's newest daily bar belongs to a trading day that is still running.
 *
 * <p>A provider that is asked for daily bars in the middle of a session answers with a row for
 * that day whose open/high/low/close are only the story so far. Computing an indicator over it
 * mixes a partial day into a series of complete ones, and the value silently changes again after
 * the close. The tools therefore ask this class about the newest bar and compute over the rest.
 *
 * <p>Resolution deliberately does NOT go through {@link Instrument#classify}: that method
 * collapses "unknown suffix" and "no suffix at all" into one kind, and those two must take
 * different paths here. The rule is:
 * <ul>
 *   <li>an ISIN carries no venue &rarr; conservative path;</li>
 *   <li>a dotted suffix &rarr; the table row for that suffix, or the conservative path when the
 *       suffix has no row;</li>
 *   <li>no dot &rarr; the {@code America/New_York} row.</li>
 * </ul>
 *
 * <p>The conservative path is time-of-day-blind on purpose — the venue's clock is unknown, so the
 * only safe answer while the UTC week is running is "the newest bar may still be moving". Being
 * stale by one day for an untabled venue beats silently borrowing New York's clock for it.
 */
@Component
public class ExchangeSessions {

    private static final Logger log = LoggerFactory.getLogger(ExchangeSessions.class);

    /** One venue's regular trading day: zone, open, end of continuous trading (all venue-local).
     *  Closing auctions run past {@code close} and are absorbed by the configured margin. */
    private record Session(ZoneId zone, LocalTime open, LocalTime close) {}

    private static Session session(String zone, String open, String close) {
        return new Session(ZoneId.of(zone), LocalTime.parse(open), LocalTime.parse(close));
    }

    /** A symbol with no dot at all. */
    private static final Session NO_SUFFIX = session("America/New_York", "09:30", "16:00");

    private static final Map<String, Session> BY_SUFFIX = Map.ofEntries(
            Map.entry("HK", session("Asia/Hong_Kong", "09:30", "16:00")),
            Map.entry("L", session("Europe/London", "08:00", "16:30")),
            Map.entry("IR", session("Europe/Dublin", "08:00", "16:30")),
            Map.entry("T", session("Asia/Tokyo", "09:00", "15:30")),
            Map.entry("DE", session("Europe/Berlin", "09:00", "17:30")),
            Map.entry("PA", session("Europe/Paris", "09:00", "17:30")),
            Map.entry("AS", session("Europe/Amsterdam", "09:00", "17:30")),
            Map.entry("BR", session("Europe/Brussels", "09:00", "17:30")),
            Map.entry("LS", session("Europe/Lisbon", "08:00", "16:30")),
            Map.entry("MI", session("Europe/Rome", "09:00", "17:30")),
            Map.entry("SW", session("Europe/Zurich", "09:00", "17:30")),
            Map.entry("MC", session("Europe/Madrid", "09:00", "17:30")),
            Map.entry("VI", session("Europe/Vienna", "09:00", "17:30")),
            Map.entry("ST", session("Europe/Stockholm", "09:00", "17:30")),
            Map.entry("CO", session("Europe/Copenhagen", "09:00", "17:00")),
            Map.entry("OL", session("Europe/Oslo", "09:00", "16:20")),
            Map.entry("HE", session("Europe/Helsinki", "10:00", "18:25")),
            Map.entry("TO", session("America/Toronto", "09:30", "16:00")),
            Map.entry("AX", session("Australia/Sydney", "10:00", "16:00")),
            Map.entry("NZ", session("Pacific/Auckland", "10:00", "16:45")));

    /** Reported as the zone of anything that did not resolve to a table row. */
    private static final String UNKNOWN_ZONE = "unknown";

    private final int marginMinutes;
    private final Clock clock;
    /** One warning per distinct unresolvable key for the life of the bean — a screening call can
     *  carry hundreds of untabled symbols and must not turn the log into a wall. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    @Autowired
    public ExchangeSessions(
            @Value("${agora.research.session-close-margin-minutes:20}") int configuredMarginMinutes,
            @Value("${agora.data.cache.ttl-seconds:120}") int cacheTtlSeconds) {
        this(configuredMarginMinutes, cacheTtlSeconds, Clock.systemUTC());
    }

    /**
     * Public rather than package-private (as in {@code IndexChangeService}) because the tool tests
     * live in {@code de.visterion.agora.tools} and must wire a fixed clock as well.
     *
     * @param configuredMarginMinutes minutes of slack past the end of continuous trading; covers
     *        the closing auction. Negative is the one configuration error that fails fast.
     * @param cacheTtlSeconds the OHLC cache TTL: the bars handed to {@link #isPartial} may be that
     *        old while the clock is read live, so the effective margin is never smaller than
     *        {@code ttl/60 + 1}. The clamp logs an ERROR but never aborts startup — an Agora that
     *        refuses to start costs every caller its indicators, which is far worse than one bar.
     */
    public ExchangeSessions(int configuredMarginMinutes, int cacheTtlSeconds, Clock clock) {
        if (configuredMarginMinutes < 0) {
            throw new IllegalArgumentException(
                    "agora.research.session-close-margin-minutes must not be negative, got "
                            + configuredMarginMinutes);
        }
        int ttlMinutes = cacheTtlSeconds <= 0 ? 0 : cacheTtlSeconds / 60 + 1;
        if (ttlMinutes > configuredMarginMinutes) {
            log.error("agora.research.session-close-margin-minutes={} is smaller than the window "
                            + "implied by agora.data.cache.ttl-seconds={} — using {} minutes instead, "
                            + "otherwise a cached bar could be treated as final while its day runs",
                    configuredMarginMinutes, cacheTtlSeconds, ttlMinutes);
        }
        this.marginMinutes = Math.max(configuredMarginMinutes, ttlMinutes);
        this.clock = clock;
    }

    /**
     * @param lastBarDate the date of the newest bar of the (de-duplicated, ascending) series
     * @return true iff that bar belongs to a trading day that is still running and must not be
     *         used for indicator values
     */
    public boolean isPartial(String symbol, LocalDate lastBarDate) {
        if (symbol == null || lastBarDate == null) return false;
        Session session = resolve(symbol);
        ZonedDateTime now;
        boolean partial;
        if (session == null) {
            now = ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            LocalDate todayUtc = now.toLocalDate();
            partial = isWeekday(todayUtc) && !lastBarDate.isBefore(todayUtc.minusDays(1));
        } else {
            now = ZonedDateTime.ofInstant(clock.instant(), session.zone());
            LocalDate todayLocal = now.toLocalDate();
            int nowSeconds = now.toLocalTime().toSecondOfDay();
            // Half-open: exactly close + margin is already out. Kept in seconds (a long) so a
            // large margin cannot wrap past midnight the way LocalTime#plusMinutes would.
            long closeSeconds = session.close().toSecondOfDay() + 60L * marginMinutes;
            boolean inSession = isWeekday(todayLocal)
                    && nowSeconds >= session.open().toSecondOfDay()
                    && nowSeconds < closeSeconds;
            partial = inSession && !lastBarDate.isBefore(todayLocal);
        }
        if (partial) {
            log.info("session guard: dropped in-progress bar symbol={} zone={} barDate={} "
                            + "localNow={} margin={}",
                    symbol, zoneId(symbol), lastBarDate, now.toLocalDateTime(), marginMinutes);
        }
        return partial;
    }

    /** The zone id this symbol resolved to, or {@code "unknown"} on the conservative path. */
    public String zoneId(String symbol) {
        Session session = resolve(symbol);
        return session == null ? UNKNOWN_ZONE : session.zone().getId();
    }

    /** @return the venue's row, or null for the conservative path. */
    private Session resolve(String symbol) {
        if (symbol == null) return null;
        if (Instrument.isIsin(symbol)) {
            warnOnce("isin", "an ISIN identifies no venue");
            return null;
        }
        int dot = symbol.lastIndexOf('.');
        if (dot <= 0) return NO_SUFFIX;
        String suffix = symbol.substring(dot + 1).toUpperCase(Locale.ROOT);
        Session session = BY_SUFFIX.get(suffix);
        if (session == null) {
            warnOnce("suffix:" + suffix, "suffix '." + suffix + "' has no session row");
            return null;
        }
        return session;
    }

    private void warnOnce(String key, String what) {
        if (warned.add(key)) {
            log.warn("session guard: {} — falling back to the conservative rule (the newest bar "
                    + "is treated as in progress on weekdays)", what);
        }
    }

    private static boolean isWeekday(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
