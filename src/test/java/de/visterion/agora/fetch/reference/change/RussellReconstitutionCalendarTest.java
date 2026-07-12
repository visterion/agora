package de.visterion.agora.fetch.reference.change;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RussellReconstitutionCalendarTest {

    // --- last-Friday-of-June math (the computed-cadence fallback) ---

    @Test void lastFridayOfJuneMatchesObservedReconstitutionDates() {
        assertThat(RussellReconstitutionCalendar.lastFridayOfJune(2024)).isEqualTo(LocalDate.of(2024, 6, 28));
        assertThat(RussellReconstitutionCalendar.lastFridayOfJune(2025)).isEqualTo(LocalDate.of(2025, 6, 27));
        assertThat(RussellReconstitutionCalendar.lastFridayOfJune(2026)).isEqualTo(LocalDate.of(2026, 6, 26));
    }

    @Test void lastFridayOfJuneIsAlwaysAFridayInJune() {
        for (int year = 2020; year <= 2040; year++) {
            LocalDate d = RussellReconstitutionCalendar.lastFridayOfJune(year);
            assertThat(d.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
            assertThat(d.getMonthValue()).isEqualTo(6);
            assertThat(d.plusWeeks(1).getMonthValue()).isEqualTo(7); // it is the LAST Friday
        }
    }

    @Test void lastWeekdayOfAprilSkipsWeekends() {
        // Apr 30 2023 was a Sunday -> Apr 28 (Fri); Apr 30 2022 a Saturday -> Apr 29 (Fri).
        assertThat(RussellReconstitutionCalendar.lastWeekdayOfApril(2023)).isEqualTo(LocalDate.of(2023, 4, 28));
        assertThat(RussellReconstitutionCalendar.lastWeekdayOfApril(2022)).isEqualTo(LocalDate.of(2022, 4, 29));
        assertThat(RussellReconstitutionCalendar.lastWeekdayOfApril(2025)).isEqualTo(LocalDate.of(2025, 4, 30));
    }

    @Test void computedPreliminaryIsFiveWeeksBeforeEffective() {
        RussellSchedule s = RussellReconstitutionCalendar.compute(2025);
        assertThat(s.effectiveDate()).isEqualTo(LocalDate.of(2025, 6, 27));
        assertThat(s.preliminaryDate()).isEqualTo(LocalDate.of(2025, 5, 23));
        assertThat(s.preliminaryDate().getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
    }

    // --- config-first behaviour ---

    @Test void configuredYearOverridesComputedCadence() {
        RussellSchedule pinned = new RussellSchedule(2030,
                LocalDate.of(2030, 4, 30), LocalDate.of(2030, 5, 20), LocalDate.of(2030, 6, 20));
        var cal = new RussellReconstitutionCalendar(Map.of(2030, pinned));
        assertThat(cal.forYear(2030)).isEqualTo(pinned);
    }

    @Test void unconfiguredYearFallsBackToComputedCadence() {
        var cal = new RussellReconstitutionCalendar(Map.of());
        assertThat(cal.forYear(2027).effectiveDate())
                .isEqualTo(RussellReconstitutionCalendar.lastFridayOfJune(2027));
    }

    @Test void loadsExplicitDatesFromClasspathResource() {
        var cal = RussellReconstitutionCalendar.fromResource("russell-schedule.yaml");
        RussellSchedule s = cal.forYear(2025);
        assertThat(s.preliminaryDate()).isEqualTo(LocalDate.of(2025, 5, 23));
        assertThat(s.effectiveDate()).isEqualTo(LocalDate.of(2025, 6, 27));
    }

    @Test void missingResourceDegradesToComputedCadence() {
        var cal = RussellReconstitutionCalendar.fromResource("does-not-exist.yaml");
        assertThat(cal.forYear(2025).effectiveDate()).isEqualTo(LocalDate.of(2025, 6, 27));
    }
}
