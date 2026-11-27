package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.ExpenseCategory;
import io.github.muzachio.moneytrail.shared.Money;
import io.github.muzachio.moneytrail.shared.TypedText;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An expense a member records, or the correction of one: the total that {@code paidBy} paid, what it
 * was for, and how it is split. {@link GroupExpenses} checks it against the group: its currency and
 * its members.
 */
record NewExpense(
        Money amount,
        LocalDate occurredOn,
        String description,
        ExpenseCategory category,
        UUID paidBy,
        SplitMethod method,
        List<SplitInput> inputs) {

    NewExpense {
        // The schema asks for an amount above zero too; this keeps the rule true for every caller.
        if (amount.minor() <= 0) {
            throw GroupExpenseException.badRequest("The amount must be above zero.");
        }
        description = clean(description);
        inputs = List.copyOf(inputs);
    }

    // The schema refuses blank descriptions, but Java's validator reads its \s as ASCII spaces only.
    private static String clean(String description) {
        String stripped = TypedText.strip(description);
        if (stripped.isEmpty()) {
            throw GroupExpenseException.badRequest("An expense needs a description.");
        }
        return stripped;
    }
}
