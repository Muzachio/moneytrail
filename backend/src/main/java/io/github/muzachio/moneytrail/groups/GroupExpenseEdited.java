package io.github.muzachio.moneytrail.groups;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A member corrected an expense: its version {@code replacesExpenseId} was reversed and {@code
 * expenseId} recorded in its place, in one transaction, which also publishes this event (see {@link
 * GroupExpenseAdded}).
 *
 * @param previousDescription with {@code previousCategory} and {@code previousAmount}: those of the
 *     version it replaces, so a listener can say what changed without looking it up
 */
public record GroupExpenseEdited(
        UUID groupId,
        UUID expenseId,
        UUID replacesExpenseId,
        String description,
        ExpenseCategory category,
        Money amount,
        LocalDate occurredOn,
        UUID paidBy,
        String previousDescription,
        ExpenseCategory previousCategory,
        Money previousAmount,
        UUID editedBy,
        Instant editedAt) {}
