package de.visterion.agora.fetch.reference.change;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Config-driven FTSE Russell reconstitution calendar. Explicit per-year dates are loaded from a
 * classpath YAML resource (authoritative, ship-first, low-risk — no scraping). For a year not
 * present in the resource the dates are computed from the published cadence:
 *
 * <ul>
 *   <li>{@code effectiveDate} = the last Friday of June;</li>
 *   <li>{@code preliminaryDate} = 5 weeks before the effective date (also a Friday);</li>
 *   <li>{@code rankDay} = the last weekday of April.</li>
 * </ul>
 *
 * The computed values reproduce the observed 2024–2026 dates exactly, so a missing config year
 * still yields correct dates; the YAML simply pins them against any future cadence change.
 */
final class RussellReconstitutionCalendar {

    private static final Logger log = LoggerFactory.getLogger(RussellReconstitutionCalendar.class);

    private final Map<Integer, RussellSchedule> byYear;

    RussellReconstitutionCalendar(Map<Integer, RussellSchedule> byYear) {
        this.byYear = byYear;
    }

    /** Loads the schedule from a classpath YAML resource; missing/broken resource -> empty map. */
    static RussellReconstitutionCalendar fromResource(String resourcePath) {
        return new RussellReconstitutionCalendar(load(resourcePath));
    }

    /** The schedule for a year: the configured entry if present, otherwise the computed cadence. */
    RussellSchedule forYear(int year) {
        RussellSchedule configured = byYear.get(year);
        return configured != null ? configured : compute(year);
    }

    /** Computes a schedule from the published cadence for a year not pinned in config. */
    static RussellSchedule compute(int year) {
        LocalDate effective = lastFridayOfJune(year);
        return new RussellSchedule(year, lastWeekdayOfApril(year), effective.minusWeeks(5), effective);
    }

    /** The last Friday of June for the given year (reconstitution effective date). */
    static LocalDate lastFridayOfJune(int year) {
        LocalDate d = LocalDate.of(year, 6, 30);
        while (d.getDayOfWeek() != DayOfWeek.FRIDAY) d = d.minusDays(1);
        return d;
    }

    /** The last weekday (Mon–Fri) of April for the given year (rank day). */
    static LocalDate lastWeekdayOfApril(int year) {
        LocalDate d = LocalDate.of(year, 4, 30);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, RussellSchedule> load(String resourcePath) {
        Map<Integer, RussellSchedule> out = new HashMap<>();
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            log.warn("Russell schedule resource {} not found; using computed cadence for all years", resourcePath);
            return out;
        }
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            if (root == null) return out;
            Object scheduleNode = root.get("schedule");
            if (!(scheduleNode instanceof Map<?, ?> schedule)) return out;
            for (Map.Entry<?, ?> e : schedule.entrySet()) {
                try {
                    int year = Integer.parseInt(String.valueOf(e.getKey()));
                    Map<String, Object> v = (Map<String, Object>) e.getValue();
                    out.put(year, new RussellSchedule(
                            year,
                            parseDate(v.get("rankDay")),
                            parseDate(v.get("preliminaryDate")),
                            parseDate(v.get("effectiveDate"))));
                } catch (RuntimeException ex) {
                    log.warn("skipping malformed Russell schedule entry {}: {}", e.getKey(), ex.toString());
                }
            }
        } catch (Exception ex) {
            log.warn("failed to read Russell schedule resource {}: {}", resourcePath, ex.toString());
        }
        return out;
    }

    private static LocalDate parseDate(Object value) {
        if (value == null) return null;
        // SnakeYAML parses unquoted ISO dates to java.util.Date; quoted stays String. Handle both.
        if (value instanceof java.util.Date d) {
            return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value).trim());
    }
}
