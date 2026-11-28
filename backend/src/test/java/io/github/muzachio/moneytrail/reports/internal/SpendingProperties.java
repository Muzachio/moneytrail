package io.github.muzachio.moneytrail.reports.internal;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.reducing;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.personal.AccountUse;
import io.github.muzachio.moneytrail.personal.CategoryLabel;
import io.github.muzachio.moneytrail.reports.internal.CategorySum.Kind;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/**
 * Whatever the categories' monthly sums are, both reports add them up to the same income and
 * spending, and their rows are what the API promises: the categories largest first, every month of
 * the period once, oldest first.
 */
class SpendingProperties {

    private static final Money ZERO = Money.zero(Currency.getInstance("EUR"));
    private static final List<UUID> EXPENSE_CATEGORIES =
            Stream.generate(UUID::randomUUID).limit(4).toList();
    private static final List<UUID> INCOME_CATEGORIES =
            Stream.generate(UUID::randomUUID).limit(2).toList();
    private static final Map<UUID, CategoryLabel> LABELS = Stream.concat(
                    EXPENSE_CATEGORIES.stream(), INCOME_CATEGORIES.stream())
            .collect(Collectors.toMap(Function.identity(), id -> new CategoryLabel(null, "Category " + id, "tag")));

    record Sums(ReportPeriod period, List<CategorySum> sums) {}

    @Property
    void bothReportsHaveTheSameIncomeAndSpending(@ForAll("sums") Sums sums) {
        SpendingByCategory byCategory = SpendingByCategory.of(sums.sums(), LABELS, ZERO);
        SpendingByMonth byMonth = SpendingByMonth.of(sums.period(), sums.sums(), ZERO);

        assertThat(byCategory.income()).isEqualTo(total(sums, Kind.INCOME).negate());
        assertThat(byCategory.spending()).isEqualTo(total(sums, Kind.EXPENSE));
        assertThat(byMonth.income()).isEqualTo(byCategory.income());
        assertThat(byMonth.spending()).isEqualTo(byCategory.spending());
    }

    @Property
    void byCategoryListsEachExpenseCategoryThatHasSpendingLargestFirst(@ForAll("sums") Sums sums) {
        SpendingByCategory report = SpendingByCategory.of(sums.sums(), LABELS, ZERO);

        Map<UUID, Money> expected = sums.sums().stream()
                .filter(sum -> sum.kind() == Kind.EXPENSE)
                .collect(groupingBy(CategorySum::categoryId, reducing(ZERO, CategorySum::amount, Money::plus)))
                .entrySet()
                .stream()
                .filter(category -> !category.getValue().isZero())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(report.categories())
                .extracting(SpendingByCategory.CategorySpending::categoryId)
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        assertThat(report.categories()).allSatisfy(category -> {
            assertThat(category.spending()).isEqualTo(expected.get(category.categoryId()));
            assertThat(category.label()).isEqualTo(LABELS.get(category.categoryId()));
        });
        assertThat(report.categories())
                .extracting(category -> category.spending().minor())
                .isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(report.categories().stream()
                        .map(SpendingByCategory.CategorySpending::spending)
                        .reduce(ZERO, Money::plus))
                .isEqualTo(report.spending());
    }

    @Property
    void byMonthHasEveryMonthOfThePeriodOnceOldestFirst(@ForAll("sums") Sums sums) {
        SpendingByMonth report = SpendingByMonth.of(sums.period(), sums.sums(), ZERO);

        assertThat(report.months())
                .extracting(SpendingByMonth.MonthTotals::month)
                .containsExactlyElementsOf(sums.period().months());
        assertThat(report.months()).allSatisfy(month -> {
            assertThat(month.income())
                    .isEqualTo(total(sums, Kind.INCOME, month.month()).negate());
            assertThat(month.spending()).isEqualTo(total(sums, Kind.EXPENSE, month.month()));
        });
    }

    /**
     * Whatever the accounts' changes, the three parts add up to the income, the income and spending
     * are the spending report's, and only the user's investment and savings accounts count as
     * invested and as the savings accounts' part.
     */
    @Property
    void theIncomeAllocationAlwaysAddsUpToTheIncome(
            @ForAll("sums") Sums sums,
            @ForAll @Size(max = 8) List<@LongRange(min = -10_000_000, max = 10_000_000) Long> changes) {
        Map<UUID, Money> byAccount = new HashMap<>();
        Map<UUID, AccountUse> uses = new HashMap<>();
        AccountUse[] allUses = AccountUse.values();
        for (int i = 0; i < changes.size(); i++) {
            UUID account = UUID.randomUUID();
            byAccount.put(account, new Money(changes.get(i), ZERO.currency()));
            // Every fourth account is not one of the user's own and must not count.
            if (i % 4 != 3) {
                uses.put(account, allUses[i % allUses.length]);
            }
        }

        IncomeAllocation allocation = IncomeAllocation.of(sums.sums(), byAccount, uses, ZERO);

        SpendingByCategory byCategory = SpendingByCategory.of(sums.sums(), LABELS, ZERO);
        assertThat(allocation.income()).isEqualTo(byCategory.income());
        assertThat(allocation.spending()).isEqualTo(byCategory.spending());
        assertThat(allocation.invested()).isEqualTo(changeOf(AccountUse.INVESTMENT, byAccount, uses));
        assertThat(allocation.savingsAccounts()).isEqualTo(changeOf(AccountUse.SAVINGS, byAccount, uses));
        assertThat(allocation.spending().plus(allocation.invested()).plus(allocation.saved()))
                .isEqualTo(allocation.income());
    }

    private static Money changeOf(AccountUse use, Map<UUID, Money> changes, Map<UUID, AccountUse> uses) {
        return changes.keySet().stream()
                .filter(account -> uses.get(account) == use)
                .map(changes::get)
                .reduce(ZERO, Money::plus);
    }

    /** A period of up to 24 months, and at most one sum per category and month in it, as the query gives. */
    @Provide
    Arbitrary<Sums> sums() {
        Arbitrary<LocalDate> from = Arbitraries.integers()
                .between(0, 3 * 365)
                .map(days -> LocalDate.parse("2025-01-01").plusDays(days));
        Arbitrary<ReportPeriod> periods = Combinators.combine(
                        from, Arbitraries.integers().between(0, 23 * 31))
                .as((start, days) -> {
                    LocalDate end = start.plusDays(days);
                    YearMonth last = YearMonth.from(start).plusMonths(ReportPeriod.MAX_MONTHS - 1);
                    return new ReportPeriod(start, end.isAfter(last.atEndOfMonth()) ? last.atEndOfMonth() : end);
                });
        return periods.flatMap(period -> sumIn(period)
                .list()
                .ofMaxSize(40)
                .uniqueElements(sum -> List.of(sum.month(), sum.categoryId()))
                .map(list -> new Sums(period, list)));
    }

    private static Arbitrary<CategorySum> sumIn(ReportPeriod period) {
        Arbitrary<YearMonth> months = Arbitraries.of(period.months());
        Arbitrary<UUID> categories =
                Arbitraries.of(Stream.concat(EXPENSE_CATEGORIES.stream(), INCOME_CATEGORIES.stream())
                        .toList());
        Arbitrary<Money> amounts = Arbitraries.longs()
                .between(-1_000_000_000L, 1_000_000_000L)
                .filter(minor -> minor != 0)
                .map(minor -> new Money(minor, ZERO.currency()));
        return Combinators.combine(months, categories, amounts)
                .as((month, category, amount) -> new CategorySum(
                        month, category, EXPENSE_CATEGORIES.contains(category) ? Kind.EXPENSE : Kind.INCOME, amount));
    }

    private static Money total(Sums sums, Kind kind) {
        return sums.sums().stream()
                .filter(sum -> sum.kind() == kind)
                .map(CategorySum::amount)
                .reduce(ZERO, Money::plus);
    }

    private static Money total(Sums sums, Kind kind, YearMonth month) {
        return sums.sums().stream()
                .filter(sum -> sum.kind() == kind && sum.month().equals(month))
                .map(CategorySum::amount)
                .reduce(ZERO, Money::plus);
    }
}
