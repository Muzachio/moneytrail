package io.github.muzachio.moneytrail.groups;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A member recorded an expense in a group. Published in the transaction that posts it, like {@link
 * GroupCreated}, so a listener through the outbox, such as the activity feed (#90), hears of it if
 * and only if the expense exists. It carries what such a listener shows, and no email.
 *
 * @param expenseId the id of the expense, which is its journal entry's
 * @param category what it was for, so a listener can show its icon
 * @param recordedBy who recorded it, who may be someone other than {@code paidBy}
 */
public record GroupExpenseAdded(
        UUID groupId,
        UUID expenseId,
        String description,
        ExpenseCategory category,
        Money amount,
        LocalDate occurredOn,
        UUID paidBy,
        UUID recordedBy,
        Instant recordedAt) {}
