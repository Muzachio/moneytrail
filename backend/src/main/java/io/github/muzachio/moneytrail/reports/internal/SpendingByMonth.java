package io.github.muzachio.moneytrail.reports.internal;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A period's income and spending, and both for each calendar month it touches.
 *
 * @param months every month of the period, oldest first, those without movements included
 */
record SpendingByMonth(Money income, Money spending, List<MonthTotals> months) {

    SpendingByMonth {
        months = List.copyOf(months);
    }

    record MonthTotals(YearMonth month, Money income, Money spending) {}

    /**
     * Adds the categories' sums up per month, and gives the months without any a zero, so that a
     * trend chart has a point for every month. Income categories are credited, so their sums are
     * negative and income is their opposite.
     */
    static SpendingByMonth of(ReportPeriod period, Collection<CategorySum> sums, Money zero) {
        Map<YearMonth, Money> incomeByMonth = new HashMap<>();
        Map<YearMonth, Money> spendingByMonth = new HashMap<>();
        for (CategorySum sum : sums) {
            switch (sum.kind()) {
                case INCOME -> incomeByMonth.merge(sum.month(), sum.amount().negate(), Money::plus);
                case EXPENSE -> spendingByMonth.merge(sum.month(), sum.amount(), Money::plus);
            }
        }
        List<MonthTotals> months = period.months().stream()
                .map(month -> new MonthTotals(
                        month, incomeByMonth.getOrDefault(month, zero), spendingByMonth.getOrDefault(month, zero)))
                .toList();
        return new SpendingByMonth(
                months.stream().map(MonthTotals::income).reduce(zero, Money::plus),
                months.stream().map(MonthTotals::spending).reduce(zero, Money::plus),
                months);
    }
}
