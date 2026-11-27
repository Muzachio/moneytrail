package io.github.muzachio.moneytrail.activity.internal;

import io.github.muzachio.moneytrail.groups.ExpenseCategory;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One thing that happened in a group, as the feed shows it. Which of the optional parts it has
 * depends on the kind (ActivityItem in api/openapi.yaml); start with {@link #of} and add them with the
 * {@code with…} methods.
 *
 * @param actorId who did it
 * @param occurredAt when it was done
 * @param subjectId the group, the member who joined or left, or the expense or payment
 * @param occurredOn the day the expense or payment happened, as recorded
 */
record Activity(
        UUID groupId,
        ActivityKind kind,
        UUID actorId,
        Instant occurredAt,
        UUID subjectId,
        @Nullable String groupName,
        @Nullable String description,
        @Nullable ExpenseCategory category,
        @Nullable Money amount,
        @Nullable String previousDescription,
        @Nullable ExpenseCategory previousCategory,
        @Nullable Money previousAmount,
        @Nullable UUID paidBy,
        @Nullable UUID from,
        @Nullable UUID to,
        @Nullable LocalDate occurredOn) {

    /** The table keeps one currency for both amounts, which are always in the group's. */
    Activity {
        if (amount != null && previousAmount != null && !amount.currency().equals(previousAmount.currency())) {
            throw new IllegalArgumentException("Both amounts of an item are in the group's currency");
        }
        if (previousAmount != null && amount == null) {
            throw new IllegalArgumentException("A previous amount goes with an amount");
        }
    }

    static Activity of(UUID groupId, ActivityKind kind, UUID actorId, Instant occurredAt, UUID subjectId) {
        return new Activity(
                groupId,
                kind,
                actorId,
                occurredAt,
                subjectId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    Activity withGroupName(String name) {
        return new Activity(
                groupId,
                kind,
                actorId,
                occurredAt,
                subjectId,
                name,
                description,
                category,
                amount,
                previousDescription,
                previousCategory,
                previousAmount,
                paidBy,
                from,
                to,
                occurredOn);
    }

    Activity withExpense(
            String description,
            ExpenseCategory category,
            Money amount,
            @Nullable UUID paidBy,
            @Nullable LocalDate occurredOn) {
        return new Activity(
                groupId,
                kind,
                actorId,
                occurredAt,
                subjectId,
                groupName,
                description,
                category,
                amount,
                previousDescription,
                previousCategory,
                previousAmount,
                paidBy,
                from,
                to,
                occurredOn);
    }

    Activity withSettlement(Money amount, UUID from, UUID to, @Nullable LocalDate occurredOn) {
        return new Activity(
                groupId,
                kind,
                actorId,
                occurredAt,
                subjectId,
                groupName,
                description,
                category,
                amount,
                previousDescription,
                previousCategory,
                previousAmount,
                paidBy,
                from,
                to,
                occurredOn);
    }

    /** What an edit replaced: a payment has no description and no category, so it passes nulls. */
    Activity withPrevious(@Nullable String description, @Nullable ExpenseCategory category, Money amount) {
        return new Activity(
                groupId,
                kind,
                actorId,
                occurredAt,
                subjectId,
                groupName,
                this.description,
                this.category,
                this.amount,
                description,
                category,
                amount,
                paidBy,
                from,
                to,
                occurredOn);
    }
}
