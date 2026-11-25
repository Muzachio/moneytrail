package io.github.muzachio.moneytrail.groups;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A member reversed an expense without correcting it, so it no longer counts. Published in the
 * transaction that posts the reversal (see {@link GroupExpenseAdded}); an edit publishes {@link
 * GroupExpenseEdited} instead.
 *
 * @param expenseId the version that was reversed
 * @param description and {@code amount}: those of that version
 */
public record GroupExpenseReversed(
        UUID groupId, UUID expenseId, String description, Money amount, UUID reversedBy, Instant reversedAt) {}
