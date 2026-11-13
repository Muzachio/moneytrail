package io.github.muzachio.moneytrail.reports.internal;

import static java.util.Comparator.comparingLong;

import io.github.muzachio.moneytrail.personal.CategoryLabel;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A period's income and spending, and its spending by expense category.
 *
 * @param categories the categories with spending, largest first
 */
record SpendingByCategory(Money income, Money spending, List<CategorySpending> categories) {

    // Ids are UUIDv7, so on a tie the category created first comes first.
    private static final Comparator<CategorySpending> LARGEST_FIRST = comparingLong(
                    (CategorySpending category) -> category.spending().minor())
            .reversed()
            .thenComparing(CategorySpending::categoryId);

    SpendingByCategory {
        categories = List.copyOf(categories);
    }

    record CategorySpending(UUID categoryId, CategoryLabel label, Money spending) {}

    /**
     * Adds the months' sums up per category. Income categories are credited, so their sums are
     * negative and income is their opposite.
     *
     * @param labels the label of every category the sums name
     */
    static SpendingByCategory of(Collection<CategorySum> sums, Map<UUID, CategoryLabel> labels, Money zero) {
        Money income = zero;
        Map<UUID, Money> spendingByCategory = new HashMap<>();
        for (CategorySum sum : sums) {
            switch (sum.kind()) {
                case INCOME -> income = income.minus(sum.amount());
                case EXPENSE -> spendingByCategory.merge(sum.categoryId(), sum.amount(), Money::plus);
            }
        }
        List<CategorySpending> categories = spendingByCategory.entrySet().stream()
                .filter(category -> !category.getValue().isZero())
                .map(category -> new CategorySpending(
                        category.getKey(), labelOf(category.getKey(), labels), category.getValue()))
                .sorted(LARGEST_FIRST)
                .toList();
        Money spending = categories.stream().map(CategorySpending::spending).reduce(zero, Money::plus);
        return new SpendingByCategory(income, spending, categories);
    }

    private static CategoryLabel labelOf(UUID categoryId, Map<UUID, CategoryLabel> labels) {
        CategoryLabel label = labels.get(categoryId);
        if (label == null) {
            throw new IllegalStateException("Category " + categoryId + " has spending but no label");
        }
        return label;
    }
}
