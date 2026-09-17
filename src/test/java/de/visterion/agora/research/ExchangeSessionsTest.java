package de.visterion.agora.research;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The completed-bar guard. Every case pins a fixed instant and the bar date its verdict depends
 * on; each lettered item pairs a positive half (asserts partial — fails on a guard that never
 * fires) with a negative half (asserts not partial — pins the boundary).
 *
 * <p>Symbols are invented. {@code ZZ} is not an assigned ISO country code, so
 * {@code ZZ0000000008} is a shape-valid, check-digit-valid ISIN that identifies nothing.
 */
class ExchangeSessionsTest {

    private static final String ISIN = "ZZ0000000008";

    private ListAppender<ILoggingEvent> appender;

    /** Default production wiring: margin 20, cache TTL 120 s (clamp inactive: 120/60+1 = 3 < 20). */
    private ExchangeSessions at(String instant) {
        return sessions(20, 120, instant);
    }

    private ExchangeSessions sessions(int marginMinutes, int ttlSeconds, String instant) {
        return new ExchangeSessions(marginMinutes, ttlSeconds,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static LocalDate d(String date) { return LocalDate.parse(date); }

    private Level originalLevel;

    /** Captures what ExchangeSessions logs, so the WARN dedupe and the clamp ERROR are testable.
     *  The per-firing line is DEBUG (kept quiet under batch load), so the logger level is raised
     *  here for the tests that must see it — restored in {@link #detachAppender}. */
    private List<ILoggingEvent> captureLogs() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ExchangeSessions.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender.list;
    }

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ExchangeSessions.class);
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
            appender = null;
        }
    }

    // (a) Hong Kong, mid-session. 2026-09-16 is a Wednesday; 06:00 UTC = 14:00 HKT,
    //     inside 09:30..16:20 (close 16:00 + margin 20). Bar dated the HK today.
    @Test void hkMidSessionWithTodaysBarIsPartial() {
        assertThat(at("2026-09-16T06:00:00Z").isPartial("ACME.HK", d("2026-09-16"))).isTrue();
    }

    // (b) Inside the margin: 08:10 UTC = 16:10 HKT < 16:20.
    @Test void hkInsideTheCloseMarginIsStillPartial() {
        assertThat(at("2026-09-16T08:10:00Z").isPartial("ACME.HK", d("2026-09-16"))).isTrue();
    }

    // (c) Same instant, margin 0 — and a test TTL of 0 s so the clamp leaves 0 intact
    //     (with the production TTL of 120 s the effective margin would be 3). 16:10 >= 16:00.
    @Test void hkWithoutAMarginIsNotPartialAfterTheBareClose() {
        assertThat(sessions(0, 0, "2026-09-16T08:10:00Z").isPartial("ACME.HK", d("2026-09-16")))
                .isFalse();
    }

    // (d) Exactly close + margin: 08:20 UTC = 16:20 HKT. The window is half-open.
    @Test void hkExactlyAtCloseAndMarginIsNotPartial() {
        assertThat(at("2026-09-16T08:20:00Z").isPartial("ACME.HK", d("2026-09-16"))).isFalse();
    }

    // (e) Provider lag: session open, but the newest bar is yesterday's. Nothing to drop —
    //     and this is exactly the blind spot named in the design's safety section: a provider
    //     that dated the in-progress bar EARLIER than the exchange-local today would slip
    //     through here. Saxo and Yahoo-with-metadata key by trading date, so it is not exercised.
    @Test void hkInSessionWithYesterdaysBarIsNotPartial() {
        assertThat(at("2026-09-16T06:00:00Z").isPartial("ACME.HK", d("2026-09-15"))).isFalse();
    }

    // (f) Pre-open: 00:30 UTC = 08:30 HKT, before 09:30.
    @Test void hkBeforeTheOpenIsNotPartial() {
        assertThat(at("2026-09-16T00:30:00Z").isPartial("ACME.HK", d("2026-09-15"))).isFalse();
    }

    // (g) Plain ticker takes the New York row. 2026-09-16 is EDT (UTC-4):
    //     19:00 UTC = 15:00 NY (inside 09:30..16:20), 20:25 UTC = 16:25 NY (outside).
    //     Bar dated the New York today in both halves.
    @Test void plainTickerFollowsNewYork() {
        assertThat(at("2026-09-16T19:00:00Z").isPartial("ACME", d("2026-09-16"))).isTrue();
        assertThat(at("2026-09-16T20:25:00Z").isPartial("ACME", d("2026-09-16"))).isFalse();
    }

    // (h) Tokyo closes 15:30; + margin 20 = 15:50. JST is UTC+9 year-round:
    //     06:45 UTC = 15:45 JST (in), 06:55 UTC = 15:55 JST (out). Bar dated the Tokyo today.
    @Test void tokyoCloseMargin() {
        assertThat(at("2026-09-16T06:45:00Z").isPartial("ACME.T", d("2026-09-16"))).isTrue();
        assertThat(at("2026-09-16T06:55:00Z").isPartial("ACME.T", d("2026-09-16"))).isFalse();
    }

    // (i) New Zealand: the local date runs ahead of the UTC date. 2026-09-16T23:00Z is
    //     Wednesday in UTC but Thursday 2026-09-17, 11:00 NZST (UTC+12) in Auckland —
    //     inside 10:00..17:05. The bar dated the NZ-local today is in progress; the bar dated
    //     the UTC today (= NZ yesterday) is the completed one and must survive.
    @Test void newZealandUsesTheLocalDateNotTheUtcDate() {
        var s = at("2026-09-16T23:00:00Z");
        assertThat(s.isPartial("ACME.NZ", d("2026-09-17"))).isTrue();
        assertThat(s.isPartial("ACME.NZ", d("2026-09-16"))).isFalse();
    }

    // (i, second half) Same shape under NZDT (UTC+13): 2026-10-14T22:30Z = Thursday
    //     2026-10-15, 11:30 NZDT. The bar dated todayUTC (2026-10-14) is completed.
    @Test void newZealandUnderSummerTimeKeepsTheCompletedBar() {
        assertThat(at("2026-10-14T22:30:00Z").isPartial("ACME.NZ", d("2026-10-14"))).isFalse();
    }

    // (j) Weekends: no session, whatever the clock says. 2026-09-19 is a Saturday;
    //     2026-09-18T23:00Z is Saturday 2026-09-19, 11:00 NZST in Auckland.
    @Test void saturdayIsNeverInSession() {
        assertThat(at("2026-09-19T06:00:00Z").isPartial("ACME.HK", d("2026-09-18"))).isFalse();
        assertThat(at("2026-09-18T23:00:00Z").isPartial("ACME.NZ", d("2026-09-18"))).isFalse();
    }

    // (k) A dotted suffix that is not a table row takes the conservative path: time-of-day
    //     blind, weekday-gated, and it drops anything dated todayUTC or todayUTC-1.
    @Test void unknownSuffixTakesTheConservativePath() {
        var logs = captureLogs();
        var s = at("2026-09-16T06:00:00Z");                       // Wednesday in UTC

        assertThat(s.isPartial("600001.SS", d("2026-09-16"))).isTrue();   // todayUTC
        assertThat(s.isPartial("600001.SS", d("2026-09-15"))).isTrue();   // todayUTC - 1
        assertThat(s.isPartial("600001.SS", d("2026-09-14"))).isFalse();  // todayUTC - 2
        assertThat(s.zoneId("600001.SS")).isEqualTo("unknown");

        // One WARN for the whole suffix, across all four calls above.
        assertThat(logs.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("SS")))
                .hasSize(1);
    }

    @Test void unknownSuffixIsStillWeekendAware() {
        assertThat(at("2026-09-19T06:00:00Z").isPartial("600001.SS", d("2026-09-18"))).isFalse();
    }

    // (k, last half) The dot decides, not any configured suffix set: a bare ticker gets New
    // York, the same stem with an untabled suffix goes conservative.
    @Test void theDotDecidesWhichPathASymbolTakes() {
        var s = at("2026-09-16T06:00:00Z");
        assertThat(s.zoneId("ACME")).isEqualTo("America/New_York");
        assertThat(s.zoneId("ACME.SS")).isEqualTo("unknown");
    }

    // (l) An ISIN carries no venue, so it is conservative — and time-blind by decision:
    //     22:04 UTC on Tuesday 2026-09-15 is outside every tabled session, yet the bar dated
    //     that day is still dropped.
    @Test void isinIsConservativeAndTimeBlind() {
        var logs = captureLogs();
        var s = at("2026-09-15T22:04:00Z");

        assertThat(s.isPartial(ISIN, d("2026-09-15"))).isTrue();
        assertThat(s.zoneId(ISIN)).isEqualTo("unknown");
        assertThat(logs.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(1);
    }

    // (m) DST is handled by the zone, not by an offset constant. London closes 16:30,
    //     + margin 20 = 16:50 LOCAL. 2026-07-15 (Wed) is BST: 15:40 UTC = 16:40 BST (in).
    //     2026-01-20 (Tue) is GMT: 15:40 UTC = 15:40 GMT (in), 16:55 UTC = 16:55 GMT (out).
    //     Bar dated the London today in every half.
    @Test void londonHandlesSummerAndWinterTime() {
        assertThat(at("2026-07-15T15:40:00Z").isPartial("ACME.L", d("2026-07-15"))).isTrue();
        assertThat(at("2026-01-20T15:40:00Z").isPartial("ACME.L", d("2026-01-20"))).isTrue();
        assertThat(at("2026-01-20T16:55:00Z").isPartial("ACME.L", d("2026-01-20"))).isFalse();
    }

    // (n) The seasonal New York case, stated as intended-conservative: 21:00 UTC is
    //     16:00 EST in January (inside the margin -> partial) but 17:00 EDT in July (outside).
    //     Bar dated the New York today in both halves.
    @Test void newYorkAtNineteenHundredUtcDependsOnTheSeason() {
        assertThat(at("2026-01-20T21:00:00Z").isPartial("ACME", d("2026-01-20"))).isTrue();
        assertThat(at("2026-07-14T21:00:00Z").isPartial("ACME", d("2026-07-14"))).isFalse();
    }

    // (o) The cache-TTL clamp. Configured 1 with TTL 120 s -> effective 3 (120/60 + 1),
    //     one ERROR naming both properties, and the behaviour to match: HK 16:02 is inside
    //     16:03, HK 16:03 is not.
    @Test void aMarginBelowTheCacheWindowIsClampedWithAnError() {
        var logs = captureLogs();

        var s = sessions(1, 120, "2026-09-16T08:02:00Z");
        assertThat(s.isPartial("ACME.HK", d("2026-09-16"))).isTrue();
        assertThat(sessions(1, 120, "2026-09-16T08:03:00Z").isPartial("ACME.HK", d("2026-09-16")))
                .isFalse();

        var errors = logs.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).isNotEmpty();
        assertThat(errors.getFirst().getFormattedMessage())
                .contains("agora.research.session-close-margin-minutes")
                .contains("agora.data.cache.ttl-seconds");
    }

    @Test void theDefaultMarginIsAboveTheCacheWindowSoNothingIsClamped() {
        var logs = captureLogs();
        // 08:10 UTC = 16:10 HKT: inside 16:20 (margin 20), which a clamped-to-3 margin would miss.
        assertThat(at("2026-09-16T08:10:00Z").isPartial("ACME.HK", d("2026-09-16"))).isTrue();
        assertThat(logs.stream().filter(e -> e.getLevel() == Level.ERROR)).isEmpty();
    }

    @Test void aNegativeMarginIsTheOnlyFailFast() {
        assertThatThrownBy(() -> sessions(-1, 120, "2026-09-16T06:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agora.research.session-close-margin-minutes");
    }

    // Observability: one DEBUG line per firing, carrying the symbol, the zone and the bar date.
    // DEBUG rather than INFO because get_indicators_batch can carry 600 symbols in one call —
    // the tools themselves log one INFO per firing (get_indicators) or one INFO summary per call
    // (get_indicators_batch) instead.
    @Test void everyFiringLogsOneDebugLine() {
        var logs = captureLogs();

        assertThat(at("2026-09-16T06:00:00Z").isPartial("ACME.HK", d("2026-09-16"))).isTrue();

        var debugs = logs.stream().filter(e -> e.getLevel() == Level.DEBUG).toList();
        assertThat(debugs).hasSize(1);
        assertThat(debugs.getFirst().getFormattedMessage())
                .contains("session guard: dropped in-progress bar")
                .contains("ACME.HK")
                .contains("Asia/Hong_Kong")
                .contains("2026-09-16");
    }

    @Test void aBarThatIsNotDroppedLogsNothing() {
        var logs = captureLogs();
        assertThat(at("2026-09-16T00:30:00Z").isPartial("ACME.HK", d("2026-09-15"))).isFalse();
        assertThat(logs.stream().filter(e -> e.getLevel() == Level.DEBUG)).isEmpty();
    }

    @Test void zoneIdReportsTheResolvedRow() {
        var s = at("2026-09-16T06:00:00Z");
        assertThat(s.zoneId("ACME.HK")).isEqualTo("Asia/Hong_Kong");
        assertThat(s.zoneId("ACME.T")).isEqualTo("Asia/Tokyo");
        assertThat(s.zoneId("ACME.DE")).isEqualTo("Europe/Berlin");
        assertThat(s.zoneId("ACME.NZ")).isEqualTo("Pacific/Auckland");
    }
}
