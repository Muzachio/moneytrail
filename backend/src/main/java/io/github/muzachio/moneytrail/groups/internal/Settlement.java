package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One version of a settlement as the API shows it: a payment from one member to another, a journal
 * entry of the group's ledger that posts {@code from +amount} and {@code to −amount} (ADR-0012).
 *
 * @param id the journal entry's id
 * @param recordedBy who recorded this version, who may be neither {@code from} nor {@code to}
 * @param edited this version corrects an earlier one
 * @param reversed a later entry cancelled this version
 */
record Settlement(
        UUID id,
        Money amount,
        LocalDate occurredOn,
        UUID from,
        UUID to,
        UUID recordedBy,
        Instant recordedAt,
        boolean edited,
        boolean reversed)
        implements Movement {}
