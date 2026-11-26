package io.github.muzachio.moneytrail.groups;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A member corrected a settlement: its version {@code replacesSettlementId} was reversed and {@code
 * settlementId} recorded in its place, in one transaction, which also publishes this event (see
 * {@link SettlementRecorded}).
 *
 * @param previousAmount the amount of the version it replaces, so a listener can say what changed
 *     without looking it up
 */
public record SettlementEdited(
        UUID groupId,
        UUID settlementId,
        UUID replacesSettlementId,
        Money amount,
        LocalDate occurredOn,
        UUID from,
        UUID to,
        Money previousAmount,
        UUID editedBy,
        Instant editedAt) {}
