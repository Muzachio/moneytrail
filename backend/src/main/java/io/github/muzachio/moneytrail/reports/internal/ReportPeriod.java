package io.github.muzachio.moneytrail.reports.internal;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

/**
 * The days a report covers, {@code from} and {@code to} included.
 *
 * <p>The longest period is 24 calendar months, so a report by month has at most 24 rows: without a
 * limit, one from year 1 to year 9999 would make the server build and send about 120 000 empty
 * months. Two years still fit a yearly view and a comparison with the year before; the limit can
 * grow without breaking any client.
 */
record ReportPeriod(LocalDate from, LocalDate to) {

    static final int MAX_MONTHS = 24;

    ReportPeriod {
        if (to.isBefore(from)) {
            throw ReportException.invalidPeriod("The period ends on " + to + ", before it starts on " + from + ".");
        }
        long months = ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1;
        if (months > MAX_MONTHS) {
            throw ReportException.invalidPeriod(
                    "The period covers " + months + " calendar months; a report covers at most " + MAX_MONTHS + ".");
        }
    }

    /** Every calendar month the period touches, oldest first, the first and the last maybe in part. */
    List<YearMonth> months() {
        YearMonth last = YearMonth.from(to);
        return Stream.iterate(YearMonth.from(from), month -> !month.isAfter(last), month -> month.plusMonths(1))
                .toList();
    }
}
