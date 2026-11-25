package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One version of a group expense as the API shows it: a journal entry of the group's ledger, read
 * back with its split.
 *
 * @param id the journal entry's id
 * @param recordedBy who recorded this version, who may be someone other than {@code paidBy}
 * @param edited this version corrects an earlier one
 * @param reversed a later entry cancelled this version
 */
record Expense(
        UUID id,
        Money amount,
        LocalDate occurredOn,
        String description,
        UUID paidBy,
        Split split,
        UUID recordedBy,
        Instant recordedAt,
        boolean edited,
        boolean reversed) {}
