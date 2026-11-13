package io.github.muzachio.moneytrail.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class ReportPeriodTests {

    @Test
    void touchesEveryCalendarMonthFromTheFirstDayToTheLastOldestFirst() {
        ReportPeriod period = new ReportPeriod(LocalDate.parse("2026-11-15"), LocalDate.parse("2027-02-03"));

        assertThat(period.months())
                .containsExactly(
                        YearMonth.parse("2026-11"),
                        YearMonth.parse("2026-12"),
                        YearMonth.parse("2027-01"),
                        YearMonth.parse("2027-02"));
    }

    @Test
    void oneDayIsAPeriodOfOneMonth() {
        LocalDate day = LocalDate.parse("2026-10-22");

        assertThat(new ReportPeriod(day, day).months()).containsExactly(YearMonth.parse("2026-10"));
    }

    @Test
    void refusesAPeriodThatEndsBeforeItStarts() {
        assertThatThrownBy(() -> new ReportPeriod(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-05-31")))
                .isInstanceOfSatisfying(
                        ReportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("invalid_period"))
                .hasMessage("The period ends on 2026-05-31, before it starts on 2026-06-01.");
    }

    @Test
    void coversAtMost24CalendarMonths() {
        ReportPeriod longest = new ReportPeriod(LocalDate.parse("2024-12-31"), LocalDate.parse("2026-11-01"));

        assertThat(longest.months()).hasSize(ReportPeriod.MAX_MONTHS);
        assertThatThrownBy(() -> new ReportPeriod(LocalDate.parse("2024-11-30"), LocalDate.parse("2026-11-01")))
                .isInstanceOfSatisfying(
                        ReportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("invalid_period"))
                .hasMessage("The period covers 25 calendar months; a report covers at most 24.");
    }
}
